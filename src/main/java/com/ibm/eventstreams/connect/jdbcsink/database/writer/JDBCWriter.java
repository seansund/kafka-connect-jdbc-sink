/*
 *
 * Copyright 2020 IBM Corporation
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
 *
 */

package com.ibm.eventstreams.connect.jdbcsink.database.writer;

import com.ibm.eventstreams.connect.jdbcsink.JDBCSinkTask;
import com.ibm.eventstreams.connect.jdbcsink.database.datasource.IDataSource;
import org.apache.kafka.connect.sink.SinkRecord;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class JDBCWriter implements IDatabaseWriter{

    private static final Logger log = LoggerFactory.getLogger(JDBCSinkTask.class);

    private final IDataSource dataSource;

    // TODO: Use the strategy pattern with upsert strategies depending on the type of database being supported.
    //  Comma separated columns, values and relationships - nested json
    private static final String INSERT_STATEMENT = "INSERT INTO %s(%, %s, %s) VALUES (%s, %s, %s)";

    public JDBCWriter(final IDataSource dataSource) {
        this.dataSource = dataSource;
    }

    private boolean checkTable(String tableName){

        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            log.info("In CheckTable - tableName " + tableName);
            String[] tableParts = tableName.split("\\.");
            log.info("Resulting array is: "+ String.join(",",tableParts));
            DatabaseMetaData dbm = connection.getMetaData();
            ResultSet table = dbm.getTables(null, tableParts[0], tableParts[1], null);

            return table.next();

        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return false;
    }

    public boolean createTable(String tableName) throws SQLException {

        final String CREATE_STATEMENT = "CREATE TABLE %s (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY)";

        try (Connection connection = this.dataSource.getConnection()){
            Statement statement = connection.createStatement();

            final String createQuery = String.format(CREATE_STATEMENT, tableName);
            log.info("CREATEQuery " + createQuery);
            statement.execute(createQuery);
            log.warn("TABLE " + tableName + " has been created");

        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return true;
    }

    private ArrayList<String> processSchema(Schema schema, String tableName) throws SQLException{
        ArrayList<String> dataFields= new ArrayList<String>();
        try (Connection connection = this.dataSource.getConnection()) {
            Statement statement = connection.createStatement();

            DatabaseMetaData dbm = connection.getMetaData();
            for (Field field : schema.fields()) {
                dataFields.add(field.name());
                log.warn("FieldNAME = " + field.name());
                String fieldName = field.name().toUpperCase();
                log.warn("FieldTYPE = " + field.schema().type());
                String fieldType = String.valueOf(field.schema().type());
                log.info("In processSchema - tableName " + tableName);
                String[] tableParts = tableName.split("\\.");
                log.info("Resulting array is: "+ String.join(",",tableParts));
                ResultSet hasColumn = dbm.getColumns(null, tableParts[0], tableParts[1], fieldName);
                Boolean rowExists = hasColumn.next();

                if(!rowExists){
                    String ALTER_STATEMENT = "ALTER TABLE %s ADD %s %s";
                    log.warn("alter Statement = " + ALTER_STATEMENT);
                    // TODO: Replace VARCHAR with fieldType above
                    String alterQuery = String.format(ALTER_STATEMENT, tableName, fieldName, "VARCHAR(255)");
                    log.info("ALTERQUERY " + alterQuery);
                    statement.execute(alterQuery);
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return dataFields;
    }

    private static String getStructField(Object structOrMap, String fieldName) {
        String field;
        log.info("FIELDNAME Value "+ fieldName.toString());
        try {
            if (structOrMap instanceof Struct) {
                field = ((Struct) structOrMap).get(fieldName).toString();
            } else if (structOrMap instanceof Map) {
                field = ((Map<?, ?>) structOrMap).get(fieldName).toString();
                if (field == null) {
                    throw new DataException(String.format("Unable to find nested field '%s'", fieldName));
                }
                return field;
            } else {
                throw new DataException(String.format(
                        "Argument not a Struct or Map. Cannot get field '%s' from %s.",
                        fieldName,
                        structOrMap
                ));
            }
        }
        catch (Exception e){
            throw e;
        }
        if (field == null) {
            throw new DataException(
                    String.format("The field '%s' does not exist in %s.", fieldName, structOrMap));
        }
        return field;
    }

    private ArrayList<String> aggregateParams(ArrayList<String> params, Object paramStruct){
        ArrayList<String> valuesFields= new ArrayList<String>();
        String value;

        for (String fieldName : params) {
            log.info("--------- Processing Fieldname = " + fieldName);
            try {
                if (paramStruct instanceof Struct) {
                    Object valueStruct = ((Struct) paramStruct).get(fieldName);
                    value = valueStruct == null ? null : valueStruct.toString();
                } else if (paramStruct instanceof Map) {
                    Object valueObject;
                    valueObject = ((Map<?, ?>) paramStruct).get(fieldName);
                    value = valueObject == null ? null : valueObject.toString();
                    if (value == null) {
                        throw new DataException(String.format("Unable to find nested field '%s'", fieldName));
                    }
                } else {
                    throw new DataException(String.format(
                            "Argument not a Struct or Map. Cannot get field '%s' from %s.",
                            fieldName,
                            paramStruct
                    ));
                }
            }
            catch (Exception e){
                throw e;
            }

            valuesFields.add(value);
        }
        return valuesFields;
    }

    @Override
    public boolean insert(String tableName, Collection<SinkRecord> records) throws SQLException {
        // tableNameFormat must be processed
        // TODO: determine if we should externalize and maintain open connections
        //  under certain circumstances.
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);

            log.info("in insert: tableName "+ tableName);
            log.info("Checktable "+ checkTable(tableName));
            if (!checkTable(tableName)) {
                createTable(tableName);
            }

            // TODO: need an SQL statement builder with potential variations depending on the platform
            final String INSERT_STATEMENT = "INSERT INTO %s(%s) VALUES ('%s')";
            Statement statement = connection.createStatement();

            //records.stream().map(log.warn)
            // TODO: we need to figure out how to map complex records into a relational database format.
            //  do we support a 1-to-1, 1-to-many, many-to-many, recursive structure on tables.
            records.forEach(record -> {
                // TODO: add record.key();
                // TODO: add record timestamp
                // TODO: key and value could be a string or a more complex object


                Object recordStruct = record.value();

                log.warn("Record Value = " + record.toString() + " <-- Record Value");
                log.warn("Record headers = [" + record.headers() + "]");
                log.warn("Record timestamp = [" + record.timestamp() + "]");

                log.warn(" --- Record Schema --- ");
                log.warn(record.valueSchema().toString());

                log.warn("[Record value = " + record.value() + " ]");

                try {
                    ArrayList<String> tableFields = processSchema(record.valueSchema(), tableName);
                    ArrayList<String> dataFields = aggregateParams(tableFields, recordStruct);

                    log.info("TableFields: "+ tableFields.size());
                    log.info("TableFields value: "+ tableFields.toString());
                    log.info("DataFields: "+ dataFields.size());
                    log.info("DataFields value: "+ dataFields.toString());

                    String listTableFields = String.join(", ", tableFields);
                    String listDataFields = String.join("', '", dataFields);

                    final String finalQuery = String.format(INSERT_STATEMENT, tableName, listTableFields, listDataFields);
                    log.info("RECORD INSERTED");

                    statement.addBatch(finalQuery);
                    log.info("Final prepared statement: '{}' //", finalQuery);
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            });

            statement.executeBatch();
            connection.commit();

        }catch (SQLException e){
            e.printStackTrace();
        }
        return true;
    }
}