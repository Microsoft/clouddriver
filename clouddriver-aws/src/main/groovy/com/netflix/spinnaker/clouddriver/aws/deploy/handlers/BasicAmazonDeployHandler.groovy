/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.deploy.handlers

import com.amazonaws.services.autoscaling.model.BlockDeviceMapping
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest
import com.google.common.annotations.VisibleForTesting
import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.aws.AwsConfiguration
import com.netflix.spinnaker.clouddriver.aws.AwsConfiguration.DeployDefaults
import com.netflix.spinnaker.clouddriver.aws.deploy.AmiIdResolver
import com.netflix.spinnaker.clouddriver.aws.deploy.AutoScalingWorker
import com.netflix.spinnaker.clouddriver.aws.deploy.BlockDeviceConfig
import com.netflix.spinnaker.clouddriver.aws.deploy.ResolvedAmiResult
import com.netflix.spinnaker.clouddriver.aws.deploy.description.BasicAmazonDeployDescription
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.LoadBalancerLookupHelper
import com.netflix.spinnaker.clouddriver.aws.deploy.ops.loadbalancer.UpsertAmazonLoadBalancerResult
import com.netflix.spinnaker.clouddriver.aws.model.AmazonAsgLifecycleHook
import com.netflix.spinnaker.clouddriver.aws.model.AmazonBlockDevice
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials
import com.netflix.spinnaker.clouddriver.aws.services.RegionScopedProviderFactory
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.deploy.DeployDescription
import com.netflix.spinnaker.clouddriver.deploy.DeployHandler
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsRepository
import groovy.transform.PackageScope

import java.util.regex.Pattern

class BasicAmazonDeployHandler implements DeployHandler<BasicAmazonDeployDescription> {
  private static final String BASE_PHASE = "DEPLOY"

  private static final KNOWN_VIRTUALIZATION_FAMILIES = [
    paravirtual: ['c1', 'c3', 'hi1', 'hs1', 'm1', 'm2', 'm3', 't1'],
    hvm: ['c3', 'c4', 'd2', 'i2', 'g2', 'r3', 'm3', 'm4', 't2']
  ]

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  private final RegionScopedProviderFactory regionScopedProviderFactory
  private final AccountCredentialsRepository accountCredentialsRepository
  private final AwsConfiguration.DeployDefaults deployDefaults

  BasicAmazonDeployHandler(RegionScopedProviderFactory regionScopedProviderFactory,
                           AccountCredentialsRepository accountCredentialsRepository,
                           AwsConfiguration.DeployDefaults deployDefaults) {
    this.regionScopedProviderFactory = regionScopedProviderFactory
    this.accountCredentialsRepository = accountCredentialsRepository
    this.deployDefaults = deployDefaults
  }

  @Override
  boolean handles(DeployDescription description) {
    description instanceof BasicAmazonDeployDescription
  }

  @Override
  DeploymentResult handle(BasicAmazonDeployDescription description, List priorOutputs) {
    task.updateStatus BASE_PHASE, "Initializing handler..."
    def deploymentResult = new DeploymentResult()
    task.updateStatus BASE_PHASE, "Preparing deployment to ${description.availabilityZones}..."
    for (Map.Entry<String, List<String>> entry : description.availabilityZones) {
      String region = entry.key

      def sourceRegionScopedProvider = buildSourceRegionScopedProvider(task, description.source)

      description = copySourceAttributes(
        sourceRegionScopedProvider, description.source.asgName, description.source.useSourceCapacity, description
      )

      List<String> availabilityZones = entry.value

      // Get the properly typed version of the description's subnetType
      def subnetType = description.subnetType

      // Get the list of load balancers that were created as part of this conglomerate job to apply to the ASG.
      List<UpsertAmazonLoadBalancerResult.LoadBalancer> suppliedLoadBalancers = (List<UpsertAmazonLoadBalancerResult.LoadBalancer>) priorOutputs.findAll {
        it instanceof UpsertAmazonLoadBalancerResult
      }?.loadBalancers?.getAt(region)

      if (!description.loadBalancers) {
        description.loadBalancers = []
      }
      description.loadBalancers.addAll (suppliedLoadBalancers?.name ?: [])

      def regionScopedProvider = regionScopedProviderFactory.forRegion(description.credentials, region)

      def loadBalancers = lookupHelper().getLoadBalancersByName(regionScopedProvider, description.loadBalancers)
      if (loadBalancers.unknownLoadBalancers) {
        throw new IllegalStateException("Unable to find load balancers named $loadBalancers.unknownLoadBalancers")
      }

      def amazonEC2 = regionScopedProvider.amazonEC2

      String classicLinkVpcId = null
      List<String> classicLinkVpcSecurityGroups = null
      if (!subnetType) {
        def result = amazonEC2.describeVpcClassicLink()
        classicLinkVpcId = result.vpcs.find { it.classicLinkEnabled }?.vpcId
        if (classicLinkVpcId) {
          Set<String> classicLinkGroupNames = []
          classicLinkGroupNames.addAll(description.classicLinkVpcSecurityGroups ?: [])
          if (deployDefaults.classicLinkSecurityGroupName) {
            classicLinkGroupNames.addAll(deployDefaults.classicLinkSecurityGroupName)
          }

          // if we have provided groups and a vpcId, resolve them back to names to handle the case of cloning
          // from a Server Group in a different region
          if (description.classicLinkVpcId && description.classicLinkVpcSecurityGroups) {
            def groupIds = classicLinkGroupNames.findAll { it.matches(~/sg-[0-9a-f]+/) } ?: []
            classicLinkGroupNames.removeAll(groupIds)
            if (groupIds) {
              def describeSG = new DescribeSecurityGroupsRequest().withGroupIds(groupIds)
              def provider = sourceRegionScopedProvider ?: regionScopedProvider
              def resolvedNames = provider.amazonEC2.describeSecurityGroups(describeSG).securityGroups.findResults {
                if (it.vpcId == description.classicLinkVpcId && groupIds.contains(it.groupId)) {
                  return it.groupName
                }
                return null
              } ?: []

              if (resolvedNames.size() != groupIds.size()) {
                throw new IllegalStateException("failed to look up classic link security groups, had $groupIds found $resolvedNames")
              }
              classicLinkGroupNames.addAll(resolvedNames)
            }
          }

          if (deployDefaults.addAppGroupsToClassicLink) {
            //if we cloned to a new cluster, don't bring along the old clusters groups
            if (description.source) {
              def srcName = Names.parseName(description.source.asgName)
              boolean mismatch = false
              if (srcName.app != description.application) {
                classicLinkGroupNames.remove(srcName.app)
                mismatch = true
              }
              if (srcName.stack && (mismatch || srcName.stack != description.stack)) {
                classicLinkGroupNames.remove("${srcName.app}-${srcName.stack}".toString())
                mismatch = true
              }
              if (srcName.detail && (mismatch || srcName.detail != description.freeFormDetails)) {
                classicLinkGroupNames.remove("${srcName.app}-${srcName.stack ?: ''}-${srcName.detail}")
              }
            }
            def groupNamesToLookUp = []
            if (!classicLinkGroupNames.contains(description.application)) {
              groupNamesToLookUp.add(description.application)
            }
            if (description.stack) {
              String stackGroup = "${description.application}-${description.stack}"
              if (!classicLinkGroupNames.contains(stackGroup)) {
                groupNamesToLookUp.add(stackGroup)
              }
            }
            if (description.freeFormDetails) {
              String clusterGroup = "${description.application}-${description.stack ?: ''}-${description.freeFormDetails}"
              if (!classicLinkGroupNames.contains(clusterGroup)) {
                groupNamesToLookUp.add(clusterGroup)
              }
            }
            if (groupNamesToLookUp && classicLinkGroupNames.size() < deployDefaults.maxClassicLinkSecurityGroups) {
              def appGroups = regionScopedProvider.securityGroupService.getSecurityGroupIds(groupNamesToLookUp, classicLinkVpcId, false)
              for (String name : groupNamesToLookUp) {
                if (appGroups.containsKey(name)) {
                  if (classicLinkGroupNames.size() < deployDefaults.maxClassicLinkSecurityGroups) {
                    classicLinkGroupNames.add(name)
                  } else {
                    task.updateStatus(BASE_PHASE, "Not adding $name to classicLinkVpcSecurityGroups, already have $deployDefaults.maxClassicLinkSecurityGroups groups")
                  }
                }
              }
            }
          }
          classicLinkVpcSecurityGroups = classicLinkGroupNames.toList()
          task.updateStatus(BASE_PHASE, "Attaching $classicLinkGroupNames as classicLinkVpcSecurityGroups")
        }
      }

      if (description.blockDevices == null) {
        description.blockDevices = BlockDeviceConfig.getBlockDevicesForInstanceType(deployDefaults, description.instanceType)
      }
      ResolvedAmiResult ami = priorOutputs.find({
        it instanceof ResolvedAmiResult && it.region == region && it.amiName == description.amiName
      }) ?: AmiIdResolver.resolveAmiIdFromAllSources(amazonEC2, region, description.amiName, description.credentials.accountId)

      if (!ami) {
        throw new IllegalArgumentException("unable to resolve AMI imageId from $description.amiName")
      }
      validateInstanceType(ami, description.instanceType)

      def account = accountCredentialsRepository.getOne(description.credentials.name)
      if (!(account instanceof NetflixAmazonCredentials)) {
        throw new IllegalArgumentException("Unsupported account type ${account.class.simpleName} for this operation")
      }

      if (description.useAmiBlockDeviceMappings) {
        description.blockDevices = convertBlockDevices(ami.blockDeviceMappings)
      }

      def autoScalingWorker = new AutoScalingWorker(
        application: description.application,
        region: region,
        credentials: description.credentials,
        stack: description.stack,
        freeFormDetails: description.freeFormDetails,
        ami: ami.amiId,
        classicLinkVpcId: classicLinkVpcId,
        classicLinkVpcSecurityGroups: classicLinkVpcSecurityGroups,
        minInstances: description.capacity.min != null ? description.capacity.min : 0,
        maxInstances: description.capacity.max != null ? description.capacity.max : 0,
        desiredInstances: description.capacity.desired != null ? description.capacity.desired : 0,
        securityGroups: description.securityGroups,
        iamRole: iamRole(description, deployDefaults),
        keyPair: description.keyPair ?: account?.defaultKeyPair,
        sequence: description.sequence,
        ignoreSequence: description.ignoreSequence,
        startDisabled: description.startDisabled,
        associatePublicIpAddress: description.associatePublicIpAddress,
        blockDevices: description.blockDevices,
        instanceType: description.instanceType,
        availabilityZones: availabilityZones,
        subnetType: subnetType,
        classicLoadBalancers: loadBalancers.classicLoadBalancers,
        targetGroupArns: loadBalancers.targetGroupArns,
        cooldown: description.cooldown,
        healthCheckGracePeriod: description.healthCheckGracePeriod,
        healthCheckType: description.healthCheckType,
        terminationPolicies: description.terminationPolicies,
        spotPrice: description.spotPrice,
        suspendedProcesses: description.suspendedProcesses,
        kernelId: description.kernelId,
        ramdiskId: description.ramdiskId,
        instanceMonitoring: description.instanceMonitoring,
        ebsOptimized: description.ebsOptimized,
        regionScopedProvider: regionScopedProvider,
        base64UserData: description.base64UserData,
        legacyUdf: description.legacyUdf,
        tags: description.tags)

      def asgName = autoScalingWorker.deploy()

      deploymentResult.serverGroupNames << "${region}:${asgName}".toString()
      deploymentResult.serverGroupNameByRegion[region] = asgName

      if (description.copySourceScalingPoliciesAndActions) {
        copyScalingPoliciesAndScheduledActions(
          task, sourceRegionScopedProvider, description.credentials, description.source.asgName, region, asgName
        )
      }

      createLifecycleHooks(task, regionScopedProvider, account, description, asgName)
    }

    return deploymentResult
  }

  @VisibleForTesting
  @PackageScope
  LoadBalancerLookupHelper lookupHelper() {
    return new LoadBalancerLookupHelper()
  }

  @VisibleForTesting
  @PackageScope
  BasicAmazonDeployDescription copySourceAttributes(RegionScopedProviderFactory.RegionScopedProvider sourceRegionScopedProvider,
                                                    String sourceAsgName, Boolean useSourceCapacity,
                                                    BasicAmazonDeployDescription description) {
    if (!sourceRegionScopedProvider) {
      if (useSourceCapacity) {
        throw new IllegalStateException("useSourceCapacity requested, but no source available")
      }
      return description
    }

    description = description.clone()

    def sourceAutoScaling = sourceRegionScopedProvider.autoScaling
    def ancestorAsgs = sourceAutoScaling.describeAutoScalingGroups(
      new DescribeAutoScalingGroupsRequest(autoScalingGroupNames: [sourceAsgName])
    ).autoScalingGroups
    def sourceAsg = ancestorAsgs.getAt(0)

    if (!sourceAsg?.launchConfigurationName) {
      if (useSourceCapacity) {
        throw new IllegalStateException("useSourceCapacity requested, but no source ASG found")
      }
      return description
    }

    if (useSourceCapacity) {
      description.capacity.min = sourceAsg.minSize
      description.capacity.max = sourceAsg.maxSize
      description.capacity.desired = sourceAsg.desiredCapacity
    }

    def sourceLaunchConfiguration = sourceRegionScopedProvider.asgService.getLaunchConfiguration(
      sourceAsg.launchConfigurationName
    )

    description.blockDevices = buildBlockDeviceMappings(deployDefaults, description, sourceLaunchConfiguration)
    description.spotPrice = description.spotPrice ?: sourceLaunchConfiguration.spotPrice

    return description
  }

  @VisibleForTesting
  @PackageScope
  void copyScalingPoliciesAndScheduledActions(Task task,
                                              RegionScopedProviderFactory.RegionScopedProvider sourceRegionScopedProvider,
                                              NetflixAmazonCredentials targetCredentials,
                                              String sourceAsgName,
                                              String targetRegion,
                                              String targetAsgName) {
    if (!sourceRegionScopedProvider) {
      return
    }

    def asgReferenceCopier = sourceRegionScopedProvider.getAsgReferenceCopier(targetCredentials, targetRegion)
    asgReferenceCopier.copyScalingPoliciesWithAlarms(task, sourceAsgName, targetAsgName)
    asgReferenceCopier.copyScheduledActionsForAsg(task, sourceAsgName, targetAsgName)
  }

  @VisibleForTesting
  @PackageScope
  void createLifecycleHooks(Task task,
                            RegionScopedProviderFactory.RegionScopedProvider targetRegionScopedProvider,
                            NetflixAmazonCredentials targetCredentials,
                            BasicAmazonDeployDescription description,
                            String targetAsgName) {

    List<AmazonAsgLifecycleHook> lifecycleHooks = getLifecycleHooks(targetCredentials, description)
    if (lifecycleHooks.size() > 0) {
      targetRegionScopedProvider.asgLifecycleHookWorker.attach(task, lifecycleHooks, targetAsgName)
    }
  }

  @VisibleForTesting
  @PackageScope
  static List<AmazonAsgLifecycleHook> getLifecycleHooks(NetflixAmazonCredentials credentials, BasicAmazonDeployDescription description) {
    List<AmazonAsgLifecycleHook> lifecycleHooks = description.lifecycleHooks ?: []
    if (description.includeAccountLifecycleHooks && credentials.lifecycleHooks?.size() > 0) {
      lifecycleHooks.addAll(credentials.lifecycleHooks.collect {
        new AmazonAsgLifecycleHook(
          roleARN: it.roleARN,
          notificationTargetARN: it.notificationTargetARN,
          lifecycleTransition: AmazonAsgLifecycleHook.Transition.valueOfName(it.lifecycleTransition),
          heartbeatTimeout: it.heartbeatTimeout,
          defaultResult: it.defaultResult ? AmazonAsgLifecycleHook.DefaultResult.valueOf(it.defaultResult) : null
        )
      })
    }
    return lifecycleHooks
  }

  @VisibleForTesting
  @PackageScope
  static List<AmazonBlockDevice> convertBlockDevices(List<BlockDeviceMapping> blockDeviceMappings) {
    blockDeviceMappings.collect {
      def device = new AmazonBlockDevice(deviceName: it.deviceName, virtualName: it.virtualName)
      it.ebs?.with {
        device.iops = iops
        device.deleteOnTermination = deleteOnTermination
        device.size = volumeSize
        device.volumeType = volumeType
        device.snapshotId = snapshotId
      }
      device
    }
  }

  static String iamRole(BasicAmazonDeployDescription description, DeployDefaults deployDefaults) {
    def iamRole = description.iamRole ?: deployDefaults.iamRole
    return description.application ? iamRole.replaceAll(Pattern.quote('{{application}}'), description.application) : iamRole
  }

  private RegionScopedProviderFactory.RegionScopedProvider buildSourceRegionScopedProvider(Task task,
                                                                                           BasicAmazonDeployDescription.Source source) {
    if (source.account && source.region && source.asgName) {
      def sourceRegion = source.region
      def sourceAsgCredentials = accountCredentialsRepository.getOne(source.account) as NetflixAmazonCredentials
      def regionScopedProvider = regionScopedProviderFactory.forRegion(sourceAsgCredentials, sourceRegion)

      def sourceAsgs = regionScopedProvider.autoScaling.describeAutoScalingGroups(
        new DescribeAutoScalingGroupsRequest(autoScalingGroupNames: [source.asgName])
      )

      if (!sourceAsgs.autoScalingGroups) {
        task.updateStatus BASE_PHASE, "Unable to locate source asg (${source.account}:${source.region}:${source.asgName})"
        return null
      }

      return regionScopedProvider
    }

    return null
  }

  private static void validateInstanceType(ResolvedAmiResult ami, String instanceType) {
    String family = instanceType?.contains('.') ? instanceType.split("\\.")[0] : ''
    boolean familyIsKnown = KNOWN_VIRTUALIZATION_FAMILIES.containsKey(ami.virtualizationType) &&
        KNOWN_VIRTUALIZATION_FAMILIES.any { it.value.contains(family) }
    if (familyIsKnown && !KNOWN_VIRTUALIZATION_FAMILIES[ami.virtualizationType].contains(family)) {
      throw new IllegalArgumentException("Instance type ${instanceType} does not support " +
          "virtualization type ${ami.virtualizationType}. Please select a different image or instance type.")
    }
  }

  /**
   * Determine block devices
   *
   * If:
   * - The source launch configuration is using default block device mappings
   * - The instance type has changed
   *
   * Then:
   * - Re-generate block device mappings based on the new instance type
   *
   * Otherwise:
   * - Continue to use any custom block device mappings (if set)
   */
  private static Collection<AmazonBlockDevice> buildBlockDeviceMappings(
    DeployDefaults deployDefaults,
    BasicAmazonDeployDescription description,
    LaunchConfiguration sourceLaunchConfiguration
  ) {
    if (description.blockDevices != null) {
      // block device mappings have been explicitly specified and should be used regardless of instance type
      return description.blockDevices
    }

    if (sourceLaunchConfiguration.instanceType != description.instanceType) {
      // instance type has changed, verify that the block device mappings are still legitimate (ebs vs. ephemeral)
      def blockDevicesForSourceAsg = sourceLaunchConfiguration.blockDeviceMappings.collect {
        [deviceName: it.deviceName, virtualName: it.virtualName, size: it.ebs?.volumeSize]
      }.sort { it.deviceName }
      def blockDevicesForSourceInstanceType = BlockDeviceConfig.getBlockDevicesForInstanceType(
        deployDefaults,
        sourceLaunchConfiguration.instanceType
      ).collect {
        [deviceName: it.deviceName, virtualName: it.virtualName, size: it.size]
      }.sort { it.deviceName }

      if (blockDevicesForSourceAsg == blockDevicesForSourceInstanceType) {
        // use default block mappings for the new instance type (since default block mappings were used on the previous instance type)
        return BlockDeviceConfig.getBlockDevicesForInstanceType(deployDefaults, description.instanceType)
      }
    }

    return convertBlockDevices(sourceLaunchConfiguration.blockDeviceMappings)
  }
}
