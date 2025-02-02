package com.hm.achievement.db;

import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import java.util.logging.Logger;

import javax.inject.Named;

import org.bukkit.configuration.file.YamlConfiguration;

import com.hm.achievement.category.NormalAchievements;

/**
 * Class used to handle a PosgreSQL database. Note that some query methods are overriden as the SQL syntax is different
 * from other database types.
 * 
 * @author Pyves
 *
 */
public class PostgreSQLDatabaseManager extends AbstractRemoteDatabaseManager {

	public PostgreSQLDatabaseManager(@Named("main") YamlConfiguration mainConfig, Logger logger,
			DatabaseUpdater databaseUpdater) {
		super(mainConfig, logger, databaseUpdater, "org.postgresql.Driver", "postgresql");
	}

	@Override
	void performPreliminaryTasks() throws ClassNotFoundException, UnsupportedEncodingException {
		super.performPreliminaryTasks();

		// Convince Maven Shade that PostgreSQL is used to prevent full exclusion during minimisation.
		@SuppressWarnings("unused")
		Class<?>[] classes = new Class<?>[] {
				org.postgresql.Driver.class
		};
	}

	@Override
	public void registerAchievement(UUID uuid, String achName, long time) {
		// PostgreSQL has no REPLACE operator. We have to use the INSERT ... ON CONFLICT construct, which is available
		// for PostgreSQL 9.5+.
		String sql = "INSERT INTO " + prefix + "achievements VALUES (?,?,?)"
				+ " ON CONFLICT (playername,achievement) DO UPDATE SET (date)=(?)";
		((SQLWriteOperation) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, uuid.toString());
				ps.setString(2, achName);
				ps.setTimestamp(3, new Timestamp(time));
				ps.setTimestamp(4, new Timestamp(time));
				ps.execute();
			}
		}).executeOperation(pool, logger, "registering an achievement");
	}

	@Override
	public int updateAndGetConnection(UUID uuid, int amount) {
		String dbName = NormalAchievements.CONNECTIONS.toDBName();
		String sqlRead = "SELECT " + dbName + " FROM " + prefix + dbName + " WHERE playername = ?";
		return ((SQLReadOperation<Integer>) () -> {
			Connection conn = getSQLConnection();
			try (PreparedStatement ps = conn.prepareStatement(sqlRead)) {
				ps.setString(1, uuid.toString());
				try (ResultSet rs = ps.executeQuery()) {
					int connections = rs.next() ? rs.getInt(dbName) + amount : amount;
					// PostgreSQL has no REPLACE operator. We have to use the INSERT ... ON CONFLICT construct, which is
					// available for PostgreSQL 9.5+.
					String sqlWrite = "INSERT INTO " + prefix + dbName + " VALUES (?,?,?)"
							+ " ON CONFLICT (playername) DO UPDATE SET (" + dbName + ",date)=(?,?)";
					((SQLWriteOperation) () -> {
						Connection writeConn = getSQLConnection();
						try (PreparedStatement writePrep = writeConn.prepareStatement(sqlWrite)) {
							String date = LocalDate.now().format(DATE_TIME_FORMATTER);
							writePrep.setString(1, uuid.toString());
							writePrep.setInt(2, connections);
							writePrep.setString(3, date);
							writePrep.setInt(4, connections);
							writePrep.setString(5, date);
							writePrep.execute();
						}
					}).executeOperation(pool, logger, "updating connection date and count");
					return connections;
				}
			}
		}).executeOperation("handling connection event");
	}
}
