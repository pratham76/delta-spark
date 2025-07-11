/*
 * Copyright (2023) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.internal.icebergcompat;

import static io.delta.kernel.internal.tablefeatures.TableFeatures.*;
import static io.delta.kernel.internal.util.Preconditions.checkArgument;
import static java.util.Collections.singletonMap;

import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.DeltaErrors;
import io.delta.kernel.internal.TableConfig;
import io.delta.kernel.internal.actions.Metadata;
import io.delta.kernel.internal.actions.Protocol;
import io.delta.kernel.internal.tablefeatures.TableFeature;
import io.delta.kernel.internal.types.TypeWideningChecker;
import io.delta.kernel.internal.util.ColumnMapping;
import io.delta.kernel.internal.util.SchemaIterable;
import io.delta.kernel.types.*;
import io.delta.kernel.utils.DataFileStatus;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Contains interfaces and common utility classes for defining the iceberg conversion compatibility
 * checks and metadata updates.
 *
 * <p>Main class is {@link IcebergCompatMetadataValidatorAndUpdater} which takes:
 *
 * <ul>
 *   <li>{@link TableConfig} to check if the table is enabled iceberg compat property enabled. When
 *       enabled, the metadata will be validated and updated.
 *   <li>List of {@link TableFeature}s expected to be supported by the protocol
 *   <li>List of {@link IcebergCompatRequiredTablePropertyEnforcer} that enforce certain properties
 *       must be set for IcebergV2 compatibility. If the property is not set, we will set it to a
 *       default value. It will also update the metadata to make it compatible with Iceberg compat
 *       version targeted.
 *   <li>List of {@link IcebergCompatCheck} to validate the metadata and protocol. The checks can be
 *       like what are the table features not supported and in what cases a certain table feature is
 *       supported (e.g. type widening is enabled, but iceberg compat only if the widening is
 *       supported in the Iceberg).
 * </ul>
 */
public abstract class IcebergCompatMetadataValidatorAndUpdater {

  /**
   * Returns whether Iceberg compatibility is enabled for the given table metadata. This checks if
   * either `icebergCompatV2` or `icebergCompatV3` table property is enabled.
   *
   * @param metadata The table metadata to check.
   * @return true if either Iceberg compatibility V2 or V3 is enabled; false otherwise.
   */
  public static Boolean isIcebergCompatEnabled(Metadata metadata) {
    return TableConfig.ICEBERG_COMPAT_V2_ENABLED.fromMetadata(metadata)
        || TableConfig.ICEBERG_COMPAT_V3_ENABLED.fromMetadata(metadata);
  }

  /////////////////////////////////////////////////////////////////////////////////
  /// Interfaces for defining checks for the compat validation and updating     ///
  /////////////////////////////////////////////////////////////////////////////////
  /** Defines the input context for the metadata validator and updater. */
  public static class IcebergCompatInputContext {
    final String compatFeatureName;
    final boolean isCreatingNewTable;
    final Metadata newMetadata;
    final Protocol newProtocol;

    public IcebergCompatInputContext(
        String compatFeatureName,
        boolean isCreatingNewTable,
        Metadata newMetadata,
        Protocol newProtocol) {
      this.compatFeatureName = compatFeatureName;
      this.isCreatingNewTable = isCreatingNewTable;
      this.newMetadata = newMetadata;
      this.newProtocol = newProtocol;
    }

    public IcebergCompatInputContext withUpdatedMetadata(Metadata newMetadata) {
      return new IcebergCompatInputContext(
          compatFeatureName, isCreatingNewTable, newMetadata, newProtocol);
    }
  }

  /** Defines a callback to post-process the metadata. */
  interface PostMetadataProcessor {
    Optional<Metadata> postProcess(IcebergCompatInputContext inputContext);
  }

  /**
   * Defines a required table property that must be set for IcebergV2 compatibility. If the property
   * is not set, we will set it to a default value. It will also update the metadata to make it
   * compatible with Iceberg compat version targeted.
   */
  protected static class IcebergCompatRequiredTablePropertyEnforcer<T> {
    public final TableConfig<T> property;
    public final Predicate<T> validator;
    public final String autoSetValue;
    public final PostMetadataProcessor postMetadataProcessor;

    /**
     * Constructor for RequiredDeltaTableProperty
     *
     * @param property DeltaConfig we are checking
     * @param validator A generic method to validate the given value
     * @param autoSetValue The value to set if we can auto-set this value (e.g. during table
     *     creation)
     * @param postMetadataProcessor A callback to post-process the metadata
     */
    IcebergCompatRequiredTablePropertyEnforcer(
        TableConfig<T> property,
        Predicate<T> validator,
        String autoSetValue,
        PostMetadataProcessor postMetadataProcessor) {
      this.property = property;
      this.validator = validator;
      this.autoSetValue = autoSetValue;
      this.postMetadataProcessor = postMetadataProcessor;
    }

    /**
     * Constructor for RequiredDeltaTableProperty
     *
     * @param property DeltaConfig we are checking
     * @param validator A generic method to validate the given value
     * @param autoSetValue The value to set if we can auto-set this value (e.g. during table
     *     creation)
     */
    IcebergCompatRequiredTablePropertyEnforcer(
        TableConfig<T> property, Predicate<T> validator, String autoSetValue) {
      this(property, validator, autoSetValue, (c) -> Optional.empty());
    }

    Optional<Metadata> validateAndUpdate(
        IcebergCompatInputContext inputContext, String compatVersion) {
      Metadata newMetadata = inputContext.newMetadata;
      T newestValue = property.fromMetadata(newMetadata);
      boolean newestValueOkay = validator.test(newestValue);
      boolean newestValueExplicitlySet =
          newMetadata.getConfiguration().containsKey(property.getKey());

      if (!newestValueOkay) {
        if (!newestValueExplicitlySet && inputContext.isCreatingNewTable) {
          // Covers the case CREATE that did not explicitly specify the required table property.
          // In these cases, we set the property automatically.
          newMetadata =
              newMetadata.withMergedConfiguration(singletonMap(property.getKey(), autoSetValue));
          return Optional.of(newMetadata);
        } else {
          // In all other cases, if the property value is not compatible
          throw new KernelException(
              String.format(
                  "The value '%s' for the property '%s' is not compatible with "
                      + "%s requirements",
                  newestValue, property.getKey(), compatVersion));
        }
      }

      return Optional.empty();
    }
  }

  /////////////////////////////////////////////////////////////////////////////////
  /// Implementation of {@link IcebergCompatRequiredTablePropertyEnforcer}      ///
  /////////////////////////////////////////////////////////////////////////////////
  protected static final IcebergCompatRequiredTablePropertyEnforcer COLUMN_MAPPING_REQUIREMENT =
      new IcebergCompatRequiredTablePropertyEnforcer<>(
          TableConfig.COLUMN_MAPPING_MODE,
          (value) ->
              ColumnMapping.ColumnMappingMode.NAME == value
                  || ColumnMapping.ColumnMappingMode.ID == value,
          ColumnMapping.ColumnMappingMode.NAME.value);

  protected static final IcebergCompatRequiredTablePropertyEnforcer ROW_TRACKING_ENABLED =
      new IcebergCompatRequiredTablePropertyEnforcer<>(
          TableConfig.ROW_TRACKING_ENABLED, (value) -> value, "true");

  /**
   * Defines checks for compatibility with the targeted iceberg features (icebergCompatV1 or
   * icebergCompatV2 etc.)
   */
  protected interface IcebergCompatCheck {
    void check(IcebergCompatInputContext inputContext);
  }

  ///////////////////////////////////////////////////////////
  /// Implementation of {@link IcebergCompatCheck}        ///
  ///////////////////////////////////////////////////////////
  protected static IcebergCompatCheck disallowOtherCompatVersions(List<String> incompatibleProps) {
    return (inputContext) -> {
      for (String prop : incompatibleProps) {
        if (Boolean.parseBoolean(
            inputContext.newMetadata.getConfiguration().getOrDefault(prop, "false"))) {
          throw DeltaErrors.icebergCompatIncompatibleVersionEnabled(
              inputContext.compatFeatureName, prop);
        }
      }
    };
  }

  protected static final IcebergCompatCheck CHECK_ONLY_ICEBERG_COMPAT_V2_ENABLED =
      disallowOtherCompatVersions(
          Arrays.asList("delta.enableIcebergCompatV1", "delta.enableIcebergCompatV3"));

  protected static final IcebergCompatCheck CHECK_ONLY_ICEBERG_COMPAT_V3_ENABLED =
      disallowOtherCompatVersions(
          Arrays.asList("delta.enableIcebergCompatV1", "delta.enableIcebergCompatV2"));

  protected static IcebergCompatCheck hasOnlySupportedTypes(
      Set<Class<? extends DataType>> supportedTypes) {
    return (inputContext) -> {
      Set<DataType> matches =
          new SchemaIterable(inputContext.newMetadata.getSchema())
              .stream()
                  .map(element -> element.getField().getDataType())
                  .filter(
                      dataType -> {
                        for (Class<? extends DataType> clazz : supportedTypes) {
                          if (clazz.isInstance(dataType)) return false;
                        }
                        return true;
                      })
                  .collect(Collectors.toSet());

      if (!matches.isEmpty()) {
        List<DataType> unsupportedTypes = new ArrayList<>(matches);
        unsupportedTypes.sort(Comparator.comparing(DataType::toString));
        throw DeltaErrors.icebergCompatUnsupportedTypeColumns(
            inputContext.compatFeatureName, unsupportedTypes);
      }
    };
  }

  private static final Set<Class<? extends DataType>> V2_SUPPORTED_TYPES =
      new HashSet<>(
          Arrays.asList(
              ByteType.class,
              ShortType.class,
              IntegerType.class,
              LongType.class,
              FloatType.class,
              DoubleType.class,
              DecimalType.class,
              StringType.class,
              BinaryType.class,
              BooleanType.class,
              DateType.class,
              TimestampType.class,
              TimestampNTZType.class,
              ArrayType.class,
              MapType.class,
              StructType.class));

  protected static final IcebergCompatCheck V2_CHECK_HAS_SUPPORTED_TYPES =
      hasOnlySupportedTypes(V2_SUPPORTED_TYPES);

  protected static final IcebergCompatCheck V3_CHECK_HAS_SUPPORTED_TYPES =
      // todo: add VariantType once it is supported
      hasOnlySupportedTypes(V2_SUPPORTED_TYPES);

  // These are the common supported partition types for both Iceberg compat V2 and V3
  protected static final IcebergCompatCheck CHECK_HAS_ALLOWED_PARTITION_TYPES =
      (inputContext) ->
          inputContext
              .newMetadata
              .getPartitionColNames()
              .forEach(
                  partitionCol -> {
                    int partitionFieldIndex =
                        inputContext.newMetadata.getSchema().indexOf(partitionCol);
                    checkArgument(
                        partitionFieldIndex != -1,
                        "Partition column %s not found in the schema",
                        partitionCol);
                    DataType dataType =
                        inputContext.newMetadata.getSchema().at(partitionFieldIndex).getDataType();
                    boolean validType =
                        dataType instanceof ByteType
                            || dataType instanceof ShortType
                            || dataType instanceof IntegerType
                            || dataType instanceof LongType
                            || dataType instanceof FloatType
                            || dataType instanceof DoubleType
                            || dataType instanceof DecimalType
                            || dataType instanceof StringType
                            || dataType instanceof BinaryType
                            || dataType instanceof BooleanType
                            || dataType instanceof DateType
                            || dataType instanceof TimestampType
                            || dataType instanceof TimestampNTZType;
                    if (!validType) {
                      throw DeltaErrors.icebergCompatUnsupportedTypePartitionColumn(
                          inputContext.compatFeatureName, dataType);
                    }
                  });

  protected static final IcebergCompatCheck CHECK_HAS_NO_PARTITION_EVOLUTION =
      (inputContext) -> {
        // TODO: Kernel doesn't support replace table yet. When it is supported, extend
        // this to allow checking the partition columns aren't changed
      };

  protected static final IcebergCompatCheck CHECK_HAS_NO_DELETION_VECTORS =
      (inputContext) -> {
        if (inputContext.newProtocol.supportsFeature(DELETION_VECTORS_RW_FEATURE)) {
          throw DeltaErrors.icebergCompatIncompatibleTableFeatures(
              inputContext.compatFeatureName, Collections.singleton(DELETION_VECTORS_RW_FEATURE));
        }
      };

  protected static final IcebergCompatCheck CHECK_HAS_SUPPORTED_TYPE_WIDENING =
      (inputContext) -> {
        Protocol protocol = inputContext.newProtocol;
        if (!protocol.supportsFeature(TYPE_WIDENING_RW_FEATURE)
            && !protocol.supportsFeature(TYPE_WIDENING_RW_PREVIEW_FEATURE)) {
          return;
        }
        for (SchemaIterable.SchemaElement element :
            new SchemaIterable(inputContext.newMetadata.getSchema())) {
          for (TypeChange typeChange : element.getField().getTypeChanges()) {
            if (!TypeWideningChecker.isIcebergV2Compatible(
                typeChange.getFrom(), typeChange.getTo())) {
              throw DeltaErrors.icebergCompatUnsupportedTypeWidening(
                  inputContext.compatFeatureName, typeChange);
            }
          }
        }
      };

  /////////////////////////////////////////////////////////////////////////////////
  /// Implementation of {@link IcebergCompatMetadataValidatorAndUpdater}        ///
  /////////////////////////////////////////////////////////////////////////////////

  /**
   * If the iceberg compat is enabled, validate and update the metadata for Iceberg compatibility.
   *
   * @param inputContext input containing the metadata, protocol, if the table is being created etc.
   * @return the updated metadata. If no updates are done, then returns empty
   * @throws {@link io.delta.kernel.exceptions.KernelException} for any validation errors
   */
  Optional<Metadata> validateAndUpdateMetadata(IcebergCompatInputContext inputContext) {
    if (!requiredDeltaTableProperty().fromMetadata(inputContext.newMetadata)) {
      return Optional.empty();
    }

    boolean metadataUpdated = false;

    // table property checks and metadata updates
    List<IcebergCompatRequiredTablePropertyEnforcer> requiredDeltaTableProperties =
        requiredDeltaTableProperties();
    for (IcebergCompatRequiredTablePropertyEnforcer requiredDeltaTableProperty :
        requiredDeltaTableProperties) {
      Optional<Metadata> updated =
          requiredDeltaTableProperty.validateAndUpdate(inputContext, compatFeatureName());

      if (updated.isPresent()) {
        inputContext = inputContext.withUpdatedMetadata(updated.get());
        metadataUpdated = true;
      }
    }

    // post-process metadata after the table property checks are done and updated
    for (IcebergCompatRequiredTablePropertyEnforcer requiredDeltaTableProperty :
        requiredDeltaTableProperties) {
      Optional<Metadata> updated =
          requiredDeltaTableProperty.postMetadataProcessor.postProcess(inputContext);
      if (updated.isPresent()) {
        metadataUpdated = true;
        inputContext = inputContext.withUpdatedMetadata(updated.get());
      }
    }

    // check for required dependency table features
    for (TableFeature requiredDependencyTableFeature : requiredDependencyTableFeatures()) {
      if (!inputContext.newProtocol.supportsFeature(requiredDependencyTableFeature)) {
        throw DeltaErrors.icebergCompatRequiredFeatureMissing(
            compatFeatureName(), requiredDependencyTableFeature.featureName());
      }
    }

    // check for Iceberg compatibility checks
    for (IcebergCompatCheck icebergCompatCheck : icebergCompatChecks()) {
      icebergCompatCheck.check(inputContext);
    }

    return metadataUpdated ? Optional.of(inputContext.newMetadata) : Optional.empty();
  }

  abstract String compatFeatureName();

  abstract TableConfig<Boolean> requiredDeltaTableProperty();

  abstract List<IcebergCompatRequiredTablePropertyEnforcer> requiredDeltaTableProperties();

  abstract List<TableFeature> requiredDependencyTableFeatures();

  abstract List<IcebergCompatCheck> icebergCompatChecks();

  /////////////////////////////
  /// Helper function       ///
  /////////////////////////////

  /**
   * Validate the given {@link DataFileStatus} that is being added as a {@code add} action to Delta
   * Log. Currently, it checks that the statistics are not empty.
   *
   * @param dataFileStatus The {@link DataFileStatus} to validate.
   * @param compatFeatureName The name of the compatibility feature being validated (e.g.
   *     "icebergCompatV2").
   */
  protected static void validateDataFileStatus(
      DataFileStatus dataFileStatus, String compatFeatureName) {
    if (!dataFileStatus.getStatistics().isPresent()) {
      // presence of stats means always has a non-null `numRecords`
      throw DeltaErrors.icebergCompatMissingNumRecordsStats(compatFeatureName, dataFileStatus);
    }
  }

  /**
   * Block the Iceberg Compat config related changes that we do not support and for which we throw
   * an {@link KernelException},
   *
   * <ul>
   *   <li>Disabling on an existing table (true to false)
   *   <li>Enabling on an existing table (false to true)
   * </ul>
   */
  protected static void blockConfigChangeOnExistingTable(
      TableConfig<Boolean> tableConfig,
      Map<String, String> oldConfig,
      Map<String, String> newConfig,
      boolean isNewTable) {
    if (!isNewTable) {
      boolean wasEnabled = tableConfig.fromMetadata(oldConfig);
      boolean isEnabled = tableConfig.fromMetadata(newConfig);
      if (!wasEnabled && isEnabled) {
        throw DeltaErrors.enablingIcebergCompatFeatureOnExistingTable(tableConfig.getKey());
      }
      if (wasEnabled && !isEnabled) {
        throw DeltaErrors.disablingIcebergCompatFeatureOnExistingTable(tableConfig.getKey());
      }
    }
  }
}
