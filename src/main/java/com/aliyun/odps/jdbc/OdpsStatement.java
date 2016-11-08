/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package com.aliyun.odps.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.odps.Column;
import com.aliyun.odps.Instance;
import com.aliyun.odps.LogView;
import com.aliyun.odps.Odps;
import com.aliyun.odps.OdpsException;
import com.aliyun.odps.OdpsType;
import com.aliyun.odps.Table;
import com.aliyun.odps.task.SQLTask;
import com.aliyun.odps.tunnel.TableTunnel;
import com.aliyun.odps.tunnel.TableTunnel.DownloadSession;
import com.aliyun.odps.tunnel.TunnelException;

public class OdpsStatement extends WrapperAdapter implements Statement {

  private OdpsConnection connHanlde;
  private Instance executeInstance = null;
  private ResultSet resultSet = null;
  private String tempTable = null;
  private int updateCount = -1;

  // when the update count is feteched by the client, set this true
  // Then the next call the getUpdateCount() will return -1, indicating there's no more results.
  // see Issue #15
  boolean updateCountFeteched = false;

  private boolean isClosed = false;
  private boolean isCancelled = false;

  private static final int POLLING_INTERVAL = 3000;
  private static final String JDBC_SQL_TASK_NAME = "jdbc_sql_task";
  private static final String SETTINGS = "settings";

  private Properties sqlTaskProperties;

  /**
   * The attributes of result set produced by this statement
   */
  protected boolean isResultSetScrollable = false;


  /**
   * The suggestion of fetch direction which might be ignored by the resultSet generated
   */
  enum FetchDirection {
    FORWARD, REVERSE, UNKNOWN
  }

  protected FetchDirection resultSetFetchDirection = FetchDirection.UNKNOWN;

  protected int resultSetMaxRows = 0;
  protected int resultSetFetchSize = 10000;

  private SQLWarning warningChain = null;

  OdpsStatement(OdpsConnection conn) {
    this(conn, false);
  }

  OdpsStatement(OdpsConnection conn, boolean isResultSetScrollable) {
    this.connHanlde = conn;
    sqlTaskProperties = (Properties) conn.getSqlTaskProperties().clone();
    this.isResultSetScrollable = isResultSetScrollable;
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void cancel() throws SQLException {
    checkClosed();
    if (isCancelled || executeInstance == null) {
      return;
    }

    try {
      executeInstance.stop();
      connHanlde.log.fine("submit cancel to instance id=" + executeInstance.getId());
    } catch (OdpsException e) {
      throw new SQLException(e);
    }

    isCancelled = true;
  }

  @Override
  public void clearBatch() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void clearWarnings() throws SQLException {
    warningChain = null;
  }

  @Override
  public void close() throws SQLException {
    if (isClosed) {
      return;
    }

    if (resultSet != null) {
      resultSet.close();
      resultSet = null;
    }

    if (tempTable != null) {
      runClientSQL("drop table " + tempTable + ";");
      connHanlde.log.fine("silently drop temp table: " + tempTable);
      tempTable = null;
    }

    connHanlde.log.fine("the statement has been closed");

    connHanlde = null;
    executeInstance = null;
    isClosed = true;
  }

  public void closeOnCompletion() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    checkClosed();
    beforeExecute();

    long begin = System.currentTimeMillis();

    // Create a temp table for querying ResultSet and ensure its creation.
    // If the table can not be created (CANCELLED/FAIL), an exception will be caused.
    // Once the table has been created, it will last until the Statement is closed,
    // or another query is started.
    String tempTempTable = "jdbc_temp_tbl_" + UUID.randomUUID().toString().replaceAll("-", "_");

    try {
      runClientSQL(
          "create table " + tempTempTable + " lifecycle " + connHanlde.lifecycle + " as " + sql);

      boolean complete = false;
      while (!complete) {
        try {
          Thread.sleep(POLLING_INTERVAL);
        } catch (InterruptedException e) {
          break;
        }

        Instance.TaskStatus.Status status;
        try {
          status = executeInstance.getTaskStatus().get(JDBC_SQL_TASK_NAME).getStatus();
        } catch (NullPointerException e) {
          continue;
        }
        switch (status) {
          case SUCCESS:
            complete = true;
            break;
          case FAILED:
            String reason = executeInstance.getTaskResults().get(JDBC_SQL_TASK_NAME);
            connHanlde.log.fine("create temp table failed: " + reason);
            throw new SQLException("create temp table failed: " + reason, "FAILED");
          case CANCELLED:
            connHanlde.log.info("create temp table cancelled");
            throw new SQLException("create temp table cancelled", "CANCELLED");
          case WAITING:
          case RUNNING:
          case SUSPENDED:
            break;
        }
      }
    } catch (OdpsException e) {
      connHanlde.log.fine("create temp table failed: " + e.getMessage());
      throw new SQLException(e);
    }

    // If we arrive here, the temp table must be effective
    tempTable = tempTempTable;
    long end = System.currentTimeMillis();
    connHanlde.log.fine("It took me " + (end - begin) + " ms to create " + tempTable);

    // Read schema
    begin = System.currentTimeMillis();
    List<String> columnNames = new ArrayList<String>();
    List<OdpsType> columnSqlTypes = new ArrayList<OdpsType>();
    try {
      Table table = connHanlde.getOdps().tables().get(tempTable);
      table.reload();
      for (Column col : table.getSchema().getColumns()) {
        columnNames.add(col.getName());
        columnSqlTypes.add(col.getType());
      }
    } catch (OdpsException e) {
      throw new SQLException(e);
    }
    OdpsResultSetMetaData meta = new OdpsResultSetMetaData(columnNames, columnSqlTypes);
    end = System.currentTimeMillis();
    connHanlde.log.fine("It took me " + (end - begin) + " ms to read the table schema");

    // Create a download session through tunnel
    DownloadSession session;
    try {
      TableTunnel tunnel = new TableTunnel(connHanlde.getOdps());
      String project_name = connHanlde.getOdps().getDefaultProject();
      session = tunnel.createDownloadSession(project_name, tempTable);
      connHanlde.log.info("create download session id=" + session.getId());
    } catch (TunnelException e) {
      throw new SQLException(e);
    }

    resultSet =
        isResultSetScrollable ? new OdpsScollResultSet(this, meta, session)
                              : new OdpsForwardResultSet(this, meta, session);

    return resultSet;
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    checkClosed();
    beforeExecute();

    long begin = System.currentTimeMillis();

    try {
      runClientSQL(sql);

      boolean complete = false;
      while (!complete) {
        try {
          Thread.sleep(POLLING_INTERVAL);
        } catch (InterruptedException e) {
          break;
        }

        Instance.TaskStatus status;
        try {
          status = executeInstance.getTaskStatus().get(JDBC_SQL_TASK_NAME);
        } catch (NullPointerException e) {
          continue;
        }
        switch (status.getStatus()) {
          case SUCCESS:
            complete = true;
            break;
          case FAILED:
            connHanlde.log.fine("update failed");
            throw new SQLException(executeInstance.getTaskResults().get(JDBC_SQL_TASK_NAME),
                                   "FAILED");
          case CANCELLED:
            connHanlde.log.info("update cancelled");
            throw new SQLException("update cancelled", "CANCELLED");
          case WAITING:
          case RUNNING:
          case SUSPENDED:
            break;
        }
      }

      executeInstance.waitForSuccess(POLLING_INTERVAL);

      long end = System.currentTimeMillis();
      connHanlde.log.fine("It took me " + (end - begin) + " ms to execute update");

      // extract update count
      Instance.TaskSummary taskSummary = executeInstance.getTaskSummary(JDBC_SQL_TASK_NAME);
      if (taskSummary != null) {
        JSONObject jsonSummary = JSON.parseObject(taskSummary.getJsonSummary());
        JSONObject outputs = jsonSummary.getJSONObject("Outputs");

        if (outputs.size() > 0) {
          updateCount = 0;
          for (Object item : outputs.values()) {
            JSONArray array = (JSONArray) item;
            updateCount += array.getInteger(0);
          }
        }
      }
    } catch (OdpsException e) {
      throw new SQLException(e);
    }

    connHanlde.log.fine("successfully updated " + updateCount + " records");
    return updateCount;
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    // short cut for SET clause
    if (processSetClause(sql)) {
      return false;
    }

    if (isQuery(sql)) {
      executeQuery(sql);
      return true;
    }
    executeUpdate(sql);
    return false;
  }

  /**
   * @param sql
   * @return
   * @throws SQLException
   */
  public static boolean isQuery(String sql) throws SQLException {
    BufferedReader reader = new BufferedReader(new StringReader(sql));
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.matches("^\\s*(--|#).*")) {  // skip the comment starting with '--' or '#'
          continue;
        }
        if (line.matches("^\\s*$")) { // skip the whitespace line
          continue;
        }
        // The first none-comment line start with "select"
        if (line.matches("(?i)^(\\s*)(SELECT).*$")) {
          return true;
        } else {
          break;
        }
      }
    } catch (IOException e) {
      throw new SQLException(e);
    }
    return false;
  }

  private boolean processSetClause(String sql) {
    if (sql.matches("(?i)^(\\s*)(SET)(\\s+)(.*)=(.*);?(\\s*)$")) {
      if (sql.contains(";")) {
        sql = sql.replace(';', ' ');
      }
      int i = sql.toLowerCase().indexOf("set");
      String pairstring = sql.substring(i + 3);
      String[] pair = pairstring.split("=");
      connHanlde.log.fine("set sql task property: " + pair[0].trim() + "=" + pair[1].trim());
      sqlTaskProperties.setProperty(pair[0].trim(), pair[1].trim());
      return true;
    }
    return false;
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public OdpsConnection getConnection() throws SQLException {
    return connHanlde;
  }

  @Override
  public int getFetchDirection() throws SQLException {
    checkClosed();

    int direction;
    switch (resultSetFetchDirection) {
      case FORWARD:
        direction = ResultSet.FETCH_FORWARD;
        break;
      case REVERSE:
        direction = ResultSet.FETCH_REVERSE;
        break;
      default:
        direction = ResultSet.FETCH_UNKNOWN;
    }
    return direction;
  }

  @Override
  public int getFetchSize() throws SQLException {
    checkClosed();
    return resultSetFetchSize;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    checkClosed();
    resultSetFetchSize = rows;
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int getMaxRows() throws SQLException {
    return resultSetMaxRows;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    if (max < 0) {
      throw new SQLException("max must be >= 0");
    }
    this.resultSetMaxRows = max;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return false;
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return resultSet;
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public int getResultSetType() throws SQLException {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    if (updateCountFeteched) {
      return -1;
    } else {
      updateCountFeteched = true;
      return updateCount;
    }
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return warningChain;
  }

  public boolean isCloseOnCompletion() throws SQLException {
    return false;
  }

  @Override
  public boolean isClosed() throws SQLException {
    return isClosed;
  }

  public boolean isPoolable() throws SQLException {
    return false;
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {

  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {

    switch (direction) {
      case ResultSet.FETCH_FORWARD:
        resultSetFetchDirection = FetchDirection.FORWARD;
        break;
      case ResultSet.FETCH_REVERSE:
        resultSetFetchDirection = FetchDirection.REVERSE;
        break;
      case ResultSet.FETCH_UNKNOWN:
        resultSetFetchDirection = FetchDirection.UNKNOWN;
        break;
      default:
        throw new SQLException("invalid argument for setFetchDirection()");
    }
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  public void setPoolable(boolean poolable) throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  private void beforeExecute() throws SQLException {
    // If the statement re-executes another query, the previously-generated resultSet
    // will be implicit closed. And the corresponding temp table will be dropped as well.
    if (resultSet != null) {
      resultSet.close();
      resultSet = null;
    }

    if (tempTable != null) {
      runClientSQL("drop table if exists " + tempTable + ";");
      connHanlde.log.fine("silently drop temp table: " + tempTable);
      tempTable = null;
    }

    executeInstance = null;
    isClosed = false;
    isCancelled = false;
    updateCount = -1;
  }

  protected Logger getParentLogger() {
    return connHanlde.log;
  }

  protected void checkClosed() throws SQLException {
    if (isClosed) {
      throw new SQLException("The statement has been closed");
    }
  }

  /**
   * Kick-offer
   *
   * @param sql
   *     sql string
   * @return an intance
   * @throws SQLException
   */
  private void runClientSQL(String sql) throws SQLException {
    try {

      // If the client forget to end with a semi-colon, append it.
      if (!sql.contains(";")) {
        sql += ";";
      }

      long begin = System.currentTimeMillis();

      Odps odps = connHanlde.getOdps();
      SQLTask sqlTask = new SQLTask();
      sqlTask.setName(JDBC_SQL_TASK_NAME);
      sqlTask.setQuery(sql);
      Map<String,String> settings = new HashMap<String,String>();
      for (String key : sqlTaskProperties.stringPropertyNames()) {
        settings.put(key, sqlTaskProperties.getProperty(key));
      }
      if (!settings.isEmpty()) {
        sqlTask.setProperty(SETTINGS, JSON.toJSONString(settings));
      }
      if (!sqlTaskProperties.isEmpty()) {
        connHanlde.log.fine("Enabled SQL task properties: " + sqlTaskProperties);
      }

      executeInstance = odps.instances().create(sqlTask);

      LogView logView = new LogView(odps);
      if (connHanlde.getLogviewHost() != null) {
        logView.setLogViewHost(connHanlde.getLogviewHost());
      }
      String logViewUrl = logView.generateLogView(executeInstance, 7 * 24);
      connHanlde.log.fine("Run SQL: " + sql);
      connHanlde.log.info(logViewUrl);
      warningChain = new SQLWarning(logViewUrl);

      // Poll the task status within the instance
      boolean complete = false;
      Instance.TaskStatus taskstatus;

      while (!complete) {
        try {
          Thread.sleep(POLLING_INTERVAL);
        } catch (InterruptedException e) {
          break;
        }

        try {
          taskstatus = executeInstance.getTaskStatus().get(JDBC_SQL_TASK_NAME);
          if (taskstatus == null) {
            connHanlde.log.warning("NullPointer when get task status");
            // NOTE: keng!!
            continue;
          }
        } catch (OdpsException e) {
          connHanlde.log.severe("Fail to get task status: " + e);
          throw new SQLException("Fail to get task status", e);
        }

        switch (taskstatus.getStatus()) {
          case SUCCESS:
            complete = true;
            connHanlde.log.fine("sql status: success");
            break;
          case FAILED:
            try {
              String reason = executeInstance.getTaskResults().get(JDBC_SQL_TASK_NAME);
              connHanlde.log.severe("execute instance failed: " + reason);
              throw new SQLException("execute instance failed: " + reason, "FAILED");
            } catch (OdpsException e) {
              connHanlde.log.severe("Fail to get task status: " + e);
              throw new SQLException("Fail to get task status", e);
            }
          case CANCELLED:
            connHanlde.log.info("execute instance cancelled");
            throw new SQLException("execute instance cancelled", "CANCELLED");
          case WAITING:
          case RUNNING:
          case SUSPENDED:
            connHanlde.log.fine("sql status: " + taskstatus.getStatus());
            break;
        }
      }

      // 等待 instance 结束
      executeInstance.waitForSuccess(POLLING_INTERVAL);
      long end = System.currentTimeMillis();
      connHanlde.log.fine("It took me " + (end - begin) + " ms to run sql");
    } catch (OdpsException e) {
      connHanlde.log.severe("Fail to run sql: " + sql);
      throw new SQLException("Fail to run sql", e);
    }
  }
  
  public Instance getExecuteInstance() {
    return executeInstance;
  }
}
