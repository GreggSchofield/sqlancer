package lama.schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import lama.DatabaseFacade;
import lama.Expression.Constant;
import lama.Main.StateToReproduce;
import lama.Randomly;
import lama.sqlite3.SQLite3Visitor;

public class Schema {

	private final List<Table> databaseTables;

	public static class Column {

		private final String name;
		private final PrimitiveDataType columnType;
		private final boolean isPrimaryKey;
		private final boolean isInteger; // "INTEGER" type, not "INT"
		private Table table;

		Column(String name, PrimitiveDataType columnType, boolean isInteger, boolean isPrimaryKey) {
			this.name = name;
			this.columnType = columnType;
			this.isInteger = isInteger;
			this.isPrimaryKey = isPrimaryKey;
			assert !isInteger || columnType == PrimitiveDataType.INT;
		}

		@Override
		public String toString() {
			return String.format("%s: %s", name, columnType);
		}

		@Override
		public int hashCode() {
			return name.hashCode() + 11 * columnType.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Column)) {
				return false;
			} else {
				Column c = (Column) obj;
				return name.equals(c.name) && columnType.equals(c.columnType);
			}
		}

		public String getName() {
			return name;
		}

		public PrimitiveDataType getColumnType() {
			return columnType;
		}
		
		public boolean isPrimaryKey() {
			return isPrimaryKey;
		}
		
		public boolean isOnlyPrimaryKey() {
			return isPrimaryKey && table.getColumns().stream().filter(c -> c.isPrimaryKey()).count() == 1;
		}

		// see https://www.sqlite.org/lang_createtable.html#rowid
		/**
		 * If a table has a single column primary key and the declared type of that column is "INTEGER" and the table is not a WITHOUT ROWID table, then the column is known as an INTEGER PRIMARY KEY. 
		 */
		public boolean isIntegerPrimaryKey() {
			return isInteger && isOnlyPrimaryKey() && !table.hasWithoutRowid();
		}

		public void setTable(Table table) {
			this.table = table;
		}

	}

	public static class Table {

		private final String tableName;
		private final List<Column> columns;

		public Table(String tableName, List<Column> columns) {
			this.tableName = tableName;
			this.columns = Collections.unmodifiableList(columns);
		}

		public boolean hasWithoutRowid() {
			// TODO FIXME implement
			return false;
		}

		@Override
		public String toString() {
			StringBuffer sb = new StringBuffer();
			sb.append(tableName + "\n");
			for (Column c : columns) {
				sb.append("\t" + c + "\n");
			}
			return sb.toString();
		}

		public String getName() {
			return tableName;
		}

		public List<Column> getColumns() {
			return columns;
		}

		public RowValue getRandomRowValue(Connection db, StateToReproduce state) throws SQLException {
			String queryStringToGetRandomTableRow = DatabaseFacade.queryStringToGetRandomTableRow(this);
			ResultSet randomRowValues = db.createStatement().executeQuery(queryStringToGetRandomTableRow);
			Map<Column, Constant> values = new HashMap<>();

			if (!randomRowValues.next()) {
				throw new AssertionError("could not find random row! " + queryStringToGetRandomTableRow + "\n" + state);
			}
			for (int i = 0; i < columns.size(); i++) {
				Column column = columns.get(i);
				int columnIndex = randomRowValues.findColumn(column.getName());
				Object value;
				String typeString = randomRowValues.getString(columnIndex + columns.size());
				PrimitiveDataType valueType = getColumnType(typeString);
				if (randomRowValues.getString(columnIndex) == null) {
					value = null;
				} else {
					switch (valueType) {
					case INT:
						value = randomRowValues.getLong(columnIndex);
						break;
					case REAL:
						value = randomRowValues.getDouble(columnIndex);
						break;
					case DATETIME:
						value = randomRowValues.getString(columnIndex);
						break;
					case TEXT:
					case NONE:
						value = randomRowValues.getString(columnIndex);
						break;
					case BINARY:
						value = randomRowValues.getBytes(columnIndex);
						break;
					default:
						throw new AssertionError();
					}
				}
				values.put(column, Constant.create(value, valueType));
			}
			assert (!randomRowValues.next());
			state.randomRowValues = values;
			return new RowValue(this, values);
		}

		public String getColumnsAsString() {
			return columns.stream().map(c -> c.getName()).collect(Collectors.joining(", "));
		}

		public String getColumnsAsString(Function<Column, String> function) {
			return columns.stream().map(function).collect(Collectors.joining(", "));
		}

	}

	public static class RowValue {
		private final Table table;
		private final Map<Column, Constant> values;

		RowValue(Table table, Map<Column, Constant> values) {
			this.table = table;
			this.values = values;
		}

		public Table getTable() {
			return table;
		}

		public Map<Column, Constant> getValues() {
			return values;
		}

		@Override
		public String toString() {
			StringBuffer sb = new StringBuffer();
			int i = 0;
			for (Column c : table.getColumns()) {
				if (i++ != 0) {
					sb.append(", ");
				}
				sb.append(values.get(c));
			}
			return sb.toString();
		}

		public String getRowValuesAsString() {
			StringBuilder sb = new StringBuilder();
			List<Column> columnsToCheck = table.getColumns();
			Map<Column, Constant> expectedValues = getValues();
			for (int i = 0; i < columnsToCheck.size(); i++) {
				if (i != 0) {
					sb.append(", ");
				}
				Constant expectedColumnValue = expectedValues.get(columnsToCheck.get(i));
				SQLite3Visitor visitor = new SQLite3Visitor();
				visitor.visit(expectedColumnValue);
				sb.append(visitor.get());
			}
			return sb.toString();
		}

	}

	public Schema(List<Table> databaseTables) {
		this.databaseTables = Collections.unmodifiableList(databaseTables);
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		for (Table t : databaseTables) {
			sb.append(t + "\n");
		}
		return sb.toString();
	}

	static public Schema fromConnection(Connection database) throws SQLException {
		DatabaseMetaData meta = database.getMetaData();
		List<Table> databaseTables = new ArrayList<>();
		ResultSet tables = meta.getTables(null, null, null, null);
		while (tables.next()) {
			String tableName = tables.getString(3);
			if (tables.getString(4).equals("SYSTEM TABLE") || tables.getString(4).equals("SYSTEM VIEW")
					|| tables.getString(4).equals("VIEW")) {
				continue;
			}
			ResultSet columns = meta.getColumns(null, null, tableName, null);
			List<Column> databaseColumns = new ArrayList<>();
			List<String> primaryKeysMap = new ArrayList<>();
			ResultSet primaryKeys = meta.getPrimaryKeys(null, null, tableName);
			while (primaryKeys.next()) {
				primaryKeysMap.add(primaryKeys.getString("COLUMN_NAME"));
			}
			while (columns.next()) {
				String columnName = columns.getString(4);
				String columnTypeString = columns.getString(6);
				PrimitiveDataType columnType = getColumnType(columnTypeString);
				databaseColumns.add(new Column(columnName, columnType, columnTypeString.contentEquals("INTEGER"), primaryKeysMap.contains(columnName)));
			}

			Table t = new Table(tableName, databaseColumns);
			for (Column c : databaseColumns) {
				c.setTable(t);
			}
			databaseTables.add(t);
		}
		assert !databaseTables.isEmpty() : database.getSchema();
		return new Schema(databaseTables);
	}

	private static PrimitiveDataType getColumnType(String columnTypeString) {
		columnTypeString = columnTypeString.toUpperCase();
		PrimitiveDataType columnType;
		switch (columnTypeString) {
		case "TEXT":
			columnType = PrimitiveDataType.TEXT;
			break;
		case "INTEGER":
			columnType = PrimitiveDataType.INT;
			break;
		case "INT":
			columnType = PrimitiveDataType.INT;
			break;
		case "DATETIME":
			columnType = PrimitiveDataType.DATETIME;
			break;
		case "":
			columnType = PrimitiveDataType.NONE;
			break;
		case "BLOB":
			columnType = PrimitiveDataType.BINARY;
			break;
		case "REAL":
			columnType = PrimitiveDataType.REAL;
			break;
		case "NULL":
			columnType = PrimitiveDataType.NULL;
			break;
		default:
			throw new AssertionError(columnTypeString);
		}
		return columnType;
	}

	Random r = new Random();

	public Table getRandomTable() {
		return Randomly.fromList(databaseTables);
	}

}
