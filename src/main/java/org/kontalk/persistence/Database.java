/*
 *  Kontalk Java client
 *  Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.kontalk.misc.JID;
import org.kontalk.misc.KonException;
import org.kontalk.model.message.KonMessage;
import org.kontalk.model.chat.Chat;
import org.kontalk.model.Contact;
import org.kontalk.model.chat.Member;
import org.kontalk.model.message.Transmission;
import org.kontalk.util.EncodingUtils;
import org.sqlite.SQLiteConfig;

/**
 * Global database for permanently storing all model information.
 * Uses the JDBC API and SQLite as DBMS.
 *
 * Database access is not concurrent safe (connection pool is needed). At least
 * writing is synchronized. Hopefully we don't see this no more:
 * "SQLException: ResultSet already requested" or "ResultSet closed"
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public final class Database {
    private static final Logger LOGGER = Logger.getLogger(Database.class.getName());

    public static final String SQL_ID = "_id INTEGER PRIMARY KEY AUTOINCREMENT, ";

    private static final String FILENAME = "kontalk_db.sqlite";
    private static final int DB_VERSION = 5;
    private static final String SQL_CREATE = "CREATE TABLE IF NOT EXISTS ";
    private static final String SV = "schema_version";
    private static final String UV = "user_version";

    private Connection mConn = null;

    public Database(Path appDir) throws KonException {
        // load the sqlite-JDBC driver using the current class loader
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            LOGGER.log(Level.SEVERE, "sqlite-JDBC driver not found", ex);
            throw new KonException(KonException.Error.DB, ex);
        }

        // create database connection
        Path path = appDir.resolve(FILENAME);
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        try {
          mConn = DriverManager.getConnection("jdbc:sqlite:" + path.toString(), config.toProperties());
        } catch(SQLException ex) {
          // if the error message is "out of memory",
          // it probably means no database file is found
          LOGGER.log(Level.SEVERE, "can't create database connection", ex);
          throw new KonException(KonException.Error.DB, ex);
        }

        try {
            // setting to false!
            mConn.setAutoCommit(false);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't set autocommit", ex);
        }

        boolean isNew;
        try (ResultSet rs = this.execQuery("PRAGMA "+SV)) {
            isNew = rs.getInt(SV) == 0;
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "can't get schema version", ex);
            throw new KonException(KonException.Error.DB, ex);
        }

        if (isNew) {
            LOGGER.info("new database, creating tables");
            try (Statement stat = mConn.createStatement()) {
                // set version
                mConn.createStatement().execute("PRAGMA "+UV+" = "+DB_VERSION);
                this.commit();
                this.createTable(stat, Contact.TABLE, Contact.SCHEMA);
                this.createTable(stat, Chat.TABLE, Chat.SCHEMA);
                this.createTable(stat, Member.TABLE, Member.SCHEMA);
                this.createTable(stat, KonMessage.TABLE, KonMessage.SCHEMA);
                this.createTable(stat, Transmission.TABLE, Transmission.SCHEMA);
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "can't create tables", ex);
                throw new KonException(KonException.Error.DB, ex);
            }
            return;
        }

        // update if needed
        int version;
        try (ResultSet rs = this.execQuery("PRAGMA "+UV)) {
            version = rs.getInt(UV);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't get db version", ex);
            return;
        }
        LOGGER.config("version: "+version);
        if (version < DB_VERSION) {
            try {
                this.update(version);
            } catch (SQLException ex) {
                LOGGER.log(Level.WARNING, "can't update db", ex);
            }
        }
    }

    private void createTable(Statement stat, String table, String schema) throws SQLException {
        stat.executeUpdate(SQL_CREATE + table + " " + schema);
    }

    private void update(int fromVersion) throws SQLException {
        if (fromVersion < 1) {
            mConn.createStatement().execute("ALTER TABLE "+Chat.TABLE+
                    " ADD COLUMN "+Chat.COL_VIEW_SET+" NOT NULL DEFAULT '{}'");
        }
        if (fromVersion < 2) {
            mConn.createStatement().execute("ALTER TABLE "+KonMessage.TABLE+
                    " ADD COLUMN "+KonMessage.COL_SERV_DATE+" DEFAULT NULL");
        }
        if (fromVersion < 3) {
            String messageTableTemp = KonMessage.TABLE + "_TEMP";
            this.createTable(mConn.createStatement(), messageTableTemp, KonMessage.SCHEMA);
            mConn.createStatement().execute("INSERT INTO "+messageTableTemp +
                    " SELECT _id, thread_id, xmpp_id, date, receipt_status, " +
                    "content, encryption_status, signing_status, coder_errors, " +
                    "server_error, server_date FROM "+KonMessage.TABLE);

            this.createTable(mConn.createStatement(), Transmission.TABLE, Transmission.SCHEMA);
            mConn.createStatement().execute("INSERT INTO "+Transmission.TABLE +
                    " SELECT NULL, _id, user_id, jid, NULL FROM "+KonMessage.TABLE);

            mConn.createStatement().execute("PRAGMA foreign_keys=OFF");
            mConn.createStatement().execute("DROP TABLE "+KonMessage.TABLE);
            mConn.createStatement().execute("ALTER TABLE "+messageTableTemp+
                    " RENAME TO "+KonMessage.TABLE);
            mConn.createStatement().execute("PRAGMA foreign_keys=ON");

            mConn.createStatement().execute("ALTER TABLE "+Chat.TABLE+
                    " ADD COLUMN "+Chat.COL_GD+" DEFAULT NULL");
        }
        if (fromVersion < 4) {
            mConn.createStatement().execute("ALTER TABLE "+Contact.TABLE+
                    " ADD COLUMN "+Contact.COL_AVATAR_ID+" DEFAULT NULL");
        }
        if (fromVersion < 5) {
            mConn.createStatement().execute("ALTER TABLE "+Member.TABLE+
                    " ADD COLUMN "+Member.COL_ROLE+" DEFAULT 0");
        }

        // set new version
        mConn.createStatement().execute("PRAGMA "+UV+" = "+DB_VERSION);
        this.commit();
        LOGGER.info("updated to version "+DB_VERSION);
    }

    public synchronized void close() {
        try {
            if(mConn == null || mConn.isClosed())
                return;
            // just to be sure
            mConn.commit();
            mConn.close();
        } catch(SQLException ex) {
            LOGGER.log(Level.WARNING, "can't close db", ex);
        }
    }

    /**
     * Select all rows from one table.
     * The returned ResultSet must be closed by the caller after usage!
     */
    public ResultSet execSelectAll(String table) throws SQLException {
        return this.execQuery("SELECT * FROM " + table);
    }

    /**
     * Select rows from one table that match an arbitrary 'where' clause.
     * Insecure to SQL injections, use with caution!
     * The returned ResultSet must be closed by the caller after usage!
     */
    public ResultSet execSelectWhereInsecure(String table, String where) throws SQLException {
        return this.execQuery("SELECT * FROM " + table + " WHERE " + where);
    }

    private ResultSet execQuery(String select) throws SQLException {
        try {
            PreparedStatement stat = mConn.prepareStatement(select);
            // does not work, i dont care
            //stat.closeOnCompletion();
            ResultSet resultSet = stat.executeQuery();
            return resultSet;
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't execute select: " + select, ex);
            throw ex;
        }
    }

    /**
     * Add a new model / row to database.
     * @param table table name the values are inserted into
     * @param values all objects / row fields that to insert
     * @return id value of inserted row, -1 if something went wrong
     */
    public synchronized int execInsert(String table, List<Object> values) {
        // first column is the id
        String insert = "INSERT INTO " + table + " VALUES (NULL,";

        List<String> vList = new ArrayList<>(values.size());
        while(vList.size() < values.size())
            vList.add("?");

        insert += StringUtils.join(vList, ", ") + ")";

        try (PreparedStatement stat = mConn.prepareStatement(insert,
                Statement.RETURN_GENERATED_KEYS)) {
            insertValues(stat, values);
            stat.executeUpdate();
            mConn.commit();
            ResultSet keys = stat.getGeneratedKeys();
            return keys.getInt(1);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't execute insert: " + insert + " " + values, ex);
            return -1;
        }
    }

    /** Update values (at most one row). */
    public synchronized void execUpdate(String table, Map<String, Object> set, int id) {
        LOGGER.config("table: "+table);
        String update = "UPDATE OR FAIL " + table + " SET ";

        List<String> keyList = new ArrayList<>(set.keySet());

        List<String> vList = keyList.stream()
                .map(key -> key + " = ?")
                .collect(Collectors.toList());

        update += StringUtils.join(vList, ", ") + " WHERE _id == " + id ;
        // note: looks like driver doesn't support "LIMIT"
        //update += " LIMIT 1";

        try (PreparedStatement stat = mConn.prepareStatement(update, Statement.RETURN_GENERATED_KEYS)) {
            insertValues(stat, keyList, set);
            stat.executeUpdate();
            mConn.commit();
            stat.getGeneratedKeys();
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't execute update: " + update + " " + set, ex);
        }
    }

    /** Delete one row. Not commited! Call commit() after deletions. */
    public boolean execDelete(String table, int id) {
        LOGGER.info("deletion, table: " + table + "; id: " + id);
        try (Statement stat = mConn.createStatement()) {
            stat.executeUpdate("DELETE FROM " + table + " WHERE _id = " + id);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't delete", ex);
            return false;
        }
        return true;
    }

    public boolean commit() {
        try {
            mConn.commit();
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't commit", ex);
            return false;
        }
        return true;
    }

    private static void insertValues(PreparedStatement stat,
            List<String> keys,
            Map<String, Object> map) throws SQLException {
        for (int i = 0; i < keys.size(); i++) {
            setValue(stat, i, map.get(keys.get(i)));
         }
    }

    private static void insertValues(PreparedStatement stat,
            List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            setValue(stat, i, values.get(i));
        }
    }

    private static void setValue(PreparedStatement stat, int i, Object value)
            throws SQLException {
        if (value instanceof String) {
                stat.setString(i+1, (String) value);
            } else if (value instanceof Integer) {
                stat.setInt(i+1, (int) value);
            } else if (value instanceof Date) {
                stat.setLong(i+1, ((Date) value).getTime());
            } else if (value instanceof Boolean) {
                stat.setBoolean(i+1, (boolean) value);
            } else if (value instanceof Enum) {
                stat.setInt(i+1, ((Enum) value).ordinal());
            } else if (value instanceof EnumSet) {
                stat.setInt(i+1, EncodingUtils.enumSetToInt(((EnumSet) value)));
            } else if (value instanceof Optional) {
                setValue(stat, i, ((Optional<?>) value).orElse(null));
            } else if (value instanceof JID) {
                stat.setString(i+1, ((JID) value).string());
            } else if (value == null) {
                stat.setNull(i+1, Types.NULL);
            } else {
                LOGGER.warning("unknown type: " + value);
            }
    }

    /**
     * Return the value for a specific column as string; the string is empty if
     * the value is SQL NULL.
     */
    public static String getString(ResultSet r, String columnLabel){
        String s;
        try {
            s = r.getString(columnLabel);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't get string from db", ex);
            return "";
        }
        return StringUtils.defaultString(s);
    }

    public static String setString(String s) {
        return s.isEmpty() ? null : s;
    }
}
