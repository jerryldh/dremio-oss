/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dremio.dac.cmd.upgrade;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.dremio.common.Version;
import com.dremio.dac.model.spaces.HomeName;
import com.dremio.exec.serialization.JacksonSerializer;
import com.dremio.exec.server.options.SystemOptionManager;
import com.dremio.exec.store.sys.PersistentStore;
import com.dremio.exec.store.sys.store.provider.KVPersistentStoreProvider;
import com.dremio.options.OptionValue;
import com.dremio.service.DirectProvider;
import com.dremio.service.accelerator.AccelerationUtils;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.dataset.proto.AccelerationSettings;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.proto.TimePeriod;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.service.reflection.ReflectionOptions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * For each source and pds migrate the deprecated 'accelerationTTL' field
 * --> refreshperiod set to accelerationTTL
 * --> graceperiod set to refreshperiod * 3
 */
public class SetAccelerationRefreshGrace extends UpgradeTask implements LegacyUpgradeTask {

  //DO NOT MODIFY
  static final String taskUUID = "3ea6ba77-3367-43ca-8e63-727b565b544b";

  private static final long HOUR_IN_MS = TimeUnit.HOURS.toMillis(1);

  public SetAccelerationRefreshGrace() {
    super("Setting acceleration refresh and grace policy", ImmutableList.of(SetDatasetExpiry.taskUUID));
  }

  @Override
  public Version getMaxVersion() {
    return VERSION_120;
  }

  @Override
  public String getTaskUUID() {
    return taskUUID;
  }

  @Override
  public void upgrade(UpgradeContext context) {
    final NamespaceService namespace = new NamespaceServiceImpl(context.getKVStoreProvider());
    boolean needToEnableSubHourPolicies = false;

    for (SourceConfig sourceConfig : namespace.getSources()) {
      NamespaceKey sourceKey = new NamespaceKey(sourceConfig.getName());
      TimePeriod accelerationTTL = sourceConfig.getAccelerationTTL();

      // skip internal (__accelerator for example)
      if (sourceConfig.getName().startsWith("__")) {
        continue;
      }

      if (accelerationTTL != null) {
        // accelerationTTL was a TimePeriod but now we store everything as millis
        Long accelerationTTLInMillis = AccelerationUtils.toMillis(accelerationTTL);
        sourceConfig.setAccelerationRefreshPeriod(accelerationTTLInMillis);
        sourceConfig.setAccelerationGracePeriod(accelerationTTLInMillis * 3L);

        System.out.println("  Updating source " + sourceConfig.getName());

        try {
          namespace.addOrUpdateSource(new NamespaceKey(sourceConfig.getName()), sourceConfig);
        } catch (NamespaceException e) {
          Throwables.propagate(e);
        }
      }

      boolean hasSubHourTTL = upgradeDatasetsForKey(sourceKey, namespace);
      if (hasSubHourTTL) {
        needToEnableSubHourPolicies = true;
      }
    }

    for (HomeConfig homeConfig : namespace.getHomeSpaces()) {
      String name = HomeName.getUserHomePath(homeConfig.getOwner()).getName();
      NamespaceKey key = new NamespaceKey(name);
      System.out.println("  Updating home space " + name);

      boolean hasSubHourTTL = upgradeDatasetsForKey(key, namespace);
      if (hasSubHourTTL) {
        needToEnableSubHourPolicies = true;
      }
    }

    // if user had sub-hour TTL, enable sub-hour acceleration policies
    if (needToEnableSubHourPolicies) {

      try(KVPersistentStoreProvider kvPersistentStoreProvider = new KVPersistentStoreProvider(
          DirectProvider.wrap(context.getKVStoreProvider()))) {
        PersistentStore<OptionValue> options = kvPersistentStoreProvider.getOrCreateStore(
            SystemOptionManager.STORE_NAME, SystemOptionManager.OptionStoreCreator.class,
            new JacksonSerializer<>(context.getLpPersistence().getMapper(), OptionValue.class));
        options.put(ReflectionOptions.ENABLE_SUBHOUR_POLICIES.getOptionName(), OptionValue.createBoolean(
            OptionValue.OptionType.SYSTEM, ReflectionOptions.ENABLE_SUBHOUR_POLICIES.getOptionName(), true));
        options.close();
      } catch (Exception e) {
        System.out.println("Could not enable sub-hour policies: " + e);
      }
    }
  }

  private boolean upgradeDatasetsForKey(NamespaceKey key, NamespaceService namespace) {
    boolean hasSubHourTTL = false;

    try {
      // find all physical datasets and update them
      final List<NamespaceKey> allDatasets = Lists.newArrayList(namespace.getAllDatasets(key));

      for (NamespaceKey datasetKey : allDatasets) {
        DatasetConfig datasetConfig = namespace.getDataset(datasetKey);
        PhysicalDataset physicalDataset = datasetConfig.getPhysicalDataset();

        if (physicalDataset != null) {
          AccelerationSettings accelerationSettings = physicalDataset.getAccelerationSettings();

          if (accelerationSettings != null) {
            TimePeriod pdsAccelerationTTL = accelerationSettings.getAccelerationTTL();

            if (pdsAccelerationTTL != null) {
              if (datasetConfig.getType() == DatasetType.PHYSICAL_DATASET_HOME_FILE) {
                // home space pds should never expire
                accelerationSettings.setRefreshPeriod(NamespaceService.INFINITE_REFRESH_PERIOD);
                accelerationSettings.setGracePeriod(NamespaceService.INFINITE_REFRESH_PERIOD);
              } else {
                // accelerationTTL was a TimePeriod but now we store everything as millis
                Long pdsAccelerationTTLInMillis = AccelerationUtils.toMillis(pdsAccelerationTTL);
                accelerationSettings.setRefreshPeriod(pdsAccelerationTTLInMillis);
                accelerationSettings.setGracePeriod(pdsAccelerationTTLInMillis * 3L);

                if (pdsAccelerationTTLInMillis < HOUR_IN_MS) {
                  hasSubHourTTL = true;
                }
              }

              physicalDataset.setAccelerationSettings(accelerationSettings);
              datasetConfig.setPhysicalDataset(physicalDataset);

              System.out.println("    Updating pds " + datasetConfig.getFullPathList());

              namespace.addOrUpdateDataset(datasetKey, datasetConfig);
            }
          }
        }
      }
    } catch (NamespaceException e) {
      Throwables.propagate(e);
    }

    return hasSubHourTTL;
  }

  @Override
  public String toString() {
    return String.format("'%s' up to %s)", getDescription(), getMaxVersion());
  }
}
