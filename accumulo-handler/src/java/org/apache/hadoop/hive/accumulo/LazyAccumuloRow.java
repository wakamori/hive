package org.apache.hadoop.hive.accumulo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.hive.accumulo.columns.ColumnEncoding;
import org.apache.hadoop.hive.accumulo.columns.ColumnMapping;
import org.apache.hadoop.hive.accumulo.columns.HiveAccumuloColumnMapping;
import org.apache.hadoop.hive.accumulo.columns.HiveAccumuloMapColumnMapping;
import org.apache.hadoop.hive.accumulo.columns.HiveAccumuloRowIdColumnMapping;
import org.apache.hadoop.hive.accumulo.serde.AccumuloRowIdFactory;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.lazy.ByteArrayRef;
import org.apache.hadoop.hive.serde2.lazy.LazyFactory;
import org.apache.hadoop.hive.serde2.lazy.LazyObjectBase;
import org.apache.hadoop.hive.serde2.lazy.LazyStruct;
import org.apache.hadoop.hive.serde2.lazy.objectinspector.LazyMapObjectInspector;
import org.apache.hadoop.hive.serde2.lazy.objectinspector.LazySimpleStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

/**
 *
 * Parses column tuples in each AccumuloHiveRow and creates Lazy objects for each field.
 *
 */
public class LazyAccumuloRow extends LazyStruct {
  private static final Logger log = Logger.getLogger(LazyAccumuloRow.class);

  private AccumuloHiveRow row;
  private List<ColumnMapping> columnMappings;
  private ArrayList<Object> cachedList = new ArrayList<Object>();
  private AccumuloRowIdFactory rowIdFactory;

  public LazyAccumuloRow(LazySimpleStructObjectInspector inspector) {
    super(inspector);
  }

  public void init(AccumuloHiveRow hiveRow, List<ColumnMapping> columnMappings,
      AccumuloRowIdFactory rowIdFactory) {
    this.row = hiveRow;
    this.columnMappings = columnMappings;
    this.rowIdFactory = rowIdFactory;
    setParsed(false);
  }

  private void parse() {
    if (getFields() == null) {
      // Will properly set string or binary serialization via createLazyField(...)
      initLazyFields(oi.getAllStructFieldRefs());
    }
    if (!getParsed()) {
      Arrays.fill(getFieldInited(), false);
      setParsed(true);
    }
  }

  @Override
  public Object getField(int id) {
    if (!getParsed()) {
      parse();
    }
    return uncheckedGetField(id);
  }

  /*
   * split pairs by delimiter.
   */
  private Object uncheckedGetField(int id) {
    if (!getFieldInited()[id]) {
      ByteArrayRef ref;
      ColumnMapping columnMapping = columnMappings.get(id);

      if (columnMapping instanceof HiveAccumuloMapColumnMapping) {
        HiveAccumuloMapColumnMapping mapColumnMapping = (HiveAccumuloMapColumnMapping) columnMapping;

        LazyAccumuloMap map = (LazyAccumuloMap) getFields()[id];
        map.init(row, mapColumnMapping);
      } else {
        if (columnMapping instanceof HiveAccumuloRowIdColumnMapping) {
          // Use the rowID directly
          ref = new ByteArrayRef();
          ref.setData(row.getRowId().getBytes());
        } else if (columnMapping instanceof HiveAccumuloColumnMapping) {
          HiveAccumuloColumnMapping accumuloColumnMapping = (HiveAccumuloColumnMapping) columnMapping;

          // Use the colfam and colqual to get the value
          byte[] val = row.getValue(new Text(accumuloColumnMapping.getColumnFamily()), new Text(
              accumuloColumnMapping.getColumnQualifier()));
          if (val == null) {
            return null;
          } else {
            ref = new ByteArrayRef();
            ref.setData(val);
          }
        } else {
          log.error("Could not process ColumnMapping of type " + columnMapping.getClass()
              + " at offset " + id + " in column mapping: " + columnMapping.getMappingSpec());
          throw new IllegalArgumentException("Cannot process ColumnMapping of type "
              + columnMapping.getClass());
        }

        getFields()[id].init(ref, 0, ref.getData().length);
      }

      // HIVE-3179 only init the field when it isn't null
      getFieldInited()[id] = true;
    }

    return getFields()[id].getObject();
  }

  @Override
  public ArrayList<Object> getFieldsAsList() {
    if (!getParsed()) {
      parse();
    }
    cachedList.clear();
    for (int i = 0; i < getFields().length; i++) {
      cachedList.add(uncheckedGetField(i));
    }
    return cachedList;
  }

  @Override
  protected LazyObjectBase createLazyField(int fieldID, StructField fieldRef) throws SerDeException {
    final ColumnMapping columnMapping = columnMappings.get(fieldID);

    if (columnMapping instanceof HiveAccumuloRowIdColumnMapping) {
      return rowIdFactory.createRowId(fieldRef.getFieldObjectInspector());
    } else if (columnMapping instanceof HiveAccumuloMapColumnMapping) {
      return new LazyAccumuloMap((LazyMapObjectInspector) fieldRef.getFieldObjectInspector());
    } else {
      return LazyFactory.createLazyObject(fieldRef.getFieldObjectInspector(),
          ColumnEncoding.BINARY == columnMapping.getEncoding());
    }
  }
}
