package com.tridev.familyhub.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.tridev.familyhub.data.local.dao.FamilyLiveStatusDao;
import com.tridev.familyhub.data.local.dao.FamilyMemberDao;
import com.tridev.familyhub.data.local.dao.FinanceEntryDao;
import com.tridev.familyhub.data.local.dao.ReminderDao;
import com.tridev.familyhub.data.local.dao.DocumentDao;
import com.tridev.familyhub.data.local.dao.PasswordEntryDao;
import com.tridev.familyhub.data.local.dao.HealthRecordDao;
import com.tridev.familyhub.data.local.dao.VehicleDao;
import com.tridev.familyhub.data.local.dao.PropertyDao;
import com.tridev.familyhub.data.local.dao.GroceryItemDao;
import com.tridev.familyhub.data.local.entity.FamilyLiveStatus;
import com.tridev.familyhub.data.local.entity.FamilyMember;
import com.tridev.familyhub.data.local.entity.FinanceEntry;
import com.tridev.familyhub.data.local.entity.Reminder;
import com.tridev.familyhub.data.local.entity.DocumentEntry;
import com.tridev.familyhub.data.local.entity.PasswordEntry;
import com.tridev.familyhub.data.local.entity.HealthRecord;
import com.tridev.familyhub.data.local.entity.Vehicle;
import com.tridev.familyhub.data.local.entity.PropertyEntry;
import com.tridev.familyhub.data.local.entity.GroceryItem;

/**
 * The private on-device database.
 *
 * UI classes should access data through repositories instead of calling
 * DAOs directly.
 */
@Database(
        entities = {
                FamilyMember.class,
                FinanceEntry.class,
                Reminder.class,
                FamilyLiveStatus.class,
                DocumentEntry.class,
                PasswordEntry.class,
                HealthRecord.class,
                Vehicle.class,
                PropertyEntry.class,
                GroceryItem.class
        },
        version = 9,
        exportSchema = false
)
public abstract class FamilyHubDatabase extends RoomDatabase {

    private static volatile FamilyHubDatabase instance;

    public abstract FamilyMemberDao familyMemberDao();

    public abstract FinanceEntryDao financeEntryDao();

    public abstract ReminderDao reminderDao();

    public abstract FamilyLiveStatusDao familyLiveStatusDao();
    public abstract DocumentDao documentDao();
    public abstract PasswordEntryDao passwordEntryDao();
    public abstract HealthRecordDao healthRecordDao();
    public abstract VehicleDao vehicleDao();
    public abstract PropertyDao propertyDao();
    public abstract GroceryItemDao groceryItemDao();

    /**
     * Preserves existing family profiles when the financial table is added.
     */
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `finance_entries` "
                            + "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`entryType` TEXT NOT NULL, "
                            + "`amount` REAL NOT NULL, "
                            + "`category` TEXT NOT NULL, "
                            + "`note` TEXT NOT NULL, "
                            + "`transactionDate` TEXT NOT NULL, "
                            + "`createdAt` INTEGER NOT NULL)"
            );

            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_finance_entries_transactionDate` "
                            + "ON `finance_entries` (`transactionDate`)"
            );

            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_finance_entries_category` "
                            + "ON `finance_entries` (`category`)"
            );
        }
    };

    /**
     * Adds offline reminders without affecting existing family or finance data.
     */
    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` "
                            + "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`title` TEXT NOT NULL, "
                            + "`note` TEXT NOT NULL, "
                            + "`reminderAt` INTEGER NOT NULL, "
                            + "`repeatType` TEXT NOT NULL, "
                            + "`isEnabled` INTEGER NOT NULL, "
                            + "`createdAt` INTEGER NOT NULL)"
            );

            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_reminders_reminderAt` "
                            + "ON `reminders` (`reminderAt`)"
            );
        }
    };

    /**
     * Adds the latest local Family Live status for each family member.
     */
    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_live_status` "
                            + "(`familyMemberId` INTEGER NOT NULL, "
                            + "`onlineStatus` TEXT NOT NULL, "
                            + "`currentPlaceName` TEXT NOT NULL, "
                            + "`latitude` REAL NOT NULL, "
                            + "`longitude` REAL NOT NULL, "
                            + "`hasLocation` INTEGER NOT NULL, "
                            + "`batteryPercentage` INTEGER NOT NULL, "
                            + "`isCharging` INTEGER NOT NULL, "
                            + "`hasInternet` INTEGER NOT NULL, "
                            + "`movementType` TEXT NOT NULL, "
                            + "`speedMetersPerSecond` REAL NOT NULL, "
                            + "`isLocationSharingEnabled` INTEGER NOT NULL, "
                            + "`visibilityType` TEXT NOT NULL, "
                            + "`lastUpdatedAt` INTEGER NOT NULL, "
                            + "PRIMARY KEY(`familyMemberId`), "
                            + "FOREIGN KEY(`familyMemberId`) "
                            + "REFERENCES `family_members`(`id`) "
                            + "ON UPDATE CASCADE ON DELETE CASCADE)"
            );

            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS "
                            + "`index_family_live_status_familyMemberId` "
                            + "ON `family_live_status` (`familyMemberId`)"
            );

            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_family_live_status_lastUpdatedAt` "
                            + "ON `family_live_status` (`lastUpdatedAt`)"
            );

            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_family_live_status_isLocationSharingEnabled` "
                            + "ON `family_live_status` "
                            + "(`isLocationSharingEnabled`)"
            );
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, `contentUri` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `expiryAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_category` ON `documents` (`category`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_expiryAt` ON `documents` (`expiryAt`)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `password_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `website` TEXT NOT NULL, `usernameEncrypted` TEXT NOT NULL, `passwordEncrypted` TEXT NOT NULL, `notesEncrypted` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_password_entries_title` ON `password_entries` (`title`)");
        }
    };

    /**
     * Adds private health records linked to existing family members.
     */
    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `health_records` "
                            + "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`familyMemberId` INTEGER NOT NULL, "
                            + "`recordType` TEXT NOT NULL, "
                            + "`title` TEXT NOT NULL, "
                            + "`value` TEXT NOT NULL, "
                            + "`notes` TEXT NOT NULL, "
                            + "`recordedAt` INTEGER NOT NULL, "
                            + "`createdAt` INTEGER NOT NULL, "
                            + "FOREIGN KEY(`familyMemberId`) "
                            + "REFERENCES `family_members`(`id`) "
                            + "ON UPDATE CASCADE ON DELETE CASCADE)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_health_records_familyMemberId` "
                            + "ON `health_records` (`familyMemberId`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_health_records_recordType` "
                            + "ON `health_records` (`recordType`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_health_records_recordedAt` "
                            + "ON `health_records` (`recordedAt`)"
            );
        }
    };

    /** Adds family-owned vehicle profiles and their important due dates. */
    private static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vehicles` "
                            + "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`ownerMemberId` INTEGER NOT NULL, "
                            + "`vehicleType` TEXT NOT NULL, "
                            + "`displayName` TEXT NOT NULL, "
                            + "`registrationNumber` TEXT NOT NULL, "
                            + "`manufacturer` TEXT NOT NULL, "
                            + "`model` TEXT NOT NULL, "
                            + "`fuelType` TEXT NOT NULL, "
                            + "`manufactureYear` INTEGER NOT NULL, "
                            + "`insuranceExpiryAt` INTEGER NOT NULL, "
                            + "`pollutionExpiryAt` INTEGER NOT NULL, "
                            + "`serviceDueAt` INTEGER NOT NULL, "
                            + "`notes` TEXT NOT NULL, "
                            + "`createdAt` INTEGER NOT NULL, "
                            + "FOREIGN KEY(`ownerMemberId`) "
                            + "REFERENCES `family_members`(`id`) "
                            + "ON UPDATE CASCADE ON DELETE CASCADE)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_vehicles_ownerMemberId` "
                            + "ON `vehicles` (`ownerMemberId`)"
            );
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS "
                            + "`index_vehicles_registrationNumber` "
                            + "ON `vehicles` (`registrationNumber`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_vehicles_insuranceExpiryAt` "
                            + "ON `vehicles` (`insuranceExpiryAt`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_vehicles_serviceDueAt` "
                            + "ON `vehicles` (`serviceDueAt`)"
            );
        }
    };

    /** Adds family-owned property profiles without changing existing data. */
    private static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `properties` "
                            + "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`ownerMemberId` INTEGER NOT NULL, "
                            + "`propertyType` TEXT NOT NULL, "
                            + "`title` TEXT NOT NULL, "
                            + "`address` TEXT NOT NULL, "
                            + "`city` TEXT NOT NULL, "
                            + "`state` TEXT NOT NULL, "
                            + "`postalCode` TEXT NOT NULL, "
                            + "`area` TEXT NOT NULL, "
                            + "`purchaseValue` REAL NOT NULL, "
                            + "`estimatedValue` REAL NOT NULL, "
                            + "`purchaseDate` INTEGER NOT NULL, "
                            + "`registrationReference` TEXT NOT NULL, "
                            + "`notes` TEXT NOT NULL, "
                            + "`createdAt` INTEGER NOT NULL, "
                            + "FOREIGN KEY(`ownerMemberId`) "
                            + "REFERENCES `family_members`(`id`) "
                            + "ON UPDATE CASCADE ON DELETE CASCADE)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_properties_ownerMemberId` "
                            + "ON `properties` (`ownerMemberId`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_properties_propertyType` "
                            + "ON `properties` (`propertyType`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_properties_title` "
                            + "ON `properties` (`title`)"
            );
        }
    };

    /** Adds the offline family grocery and shopping list. */
    private static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `grocery_items` "
                            + "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                            + "`name` TEXT NOT NULL, "
                            + "`category` TEXT NOT NULL, "
                            + "`quantity` TEXT NOT NULL, "
                            + "`estimatedCost` REAL NOT NULL, "
                            + "`priority` TEXT NOT NULL, "
                            + "`isPurchased` INTEGER NOT NULL, "
                            + "`notes` TEXT NOT NULL, "
                            + "`createdAt` INTEGER NOT NULL, "
                            + "`purchasedAt` INTEGER NOT NULL)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_grocery_items_category` "
                            + "ON `grocery_items` (`category`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_grocery_items_isPurchased` "
                            + "ON `grocery_items` (`isPurchased`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS "
                            + "`index_grocery_items_priority` "
                            + "ON `grocery_items` (`priority`)"
            );
        }
    };

    public static FamilyHubDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (FamilyHubDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    FamilyHubDatabase.class,
                                    "family_hub.db"
                            )
                            .addMigrations(
                                    MIGRATION_1_2,
                                    MIGRATION_2_3,
                                    MIGRATION_3_4,
                                    MIGRATION_4_5,
                                    MIGRATION_5_6,
                                    MIGRATION_6_7,
                                    MIGRATION_7_8,
                                    MIGRATION_8_9
                            )
                            .build();
                }
            }
        }

        return instance;
    }
}
