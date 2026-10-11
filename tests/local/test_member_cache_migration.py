"""Exercise the actual additive migration against SQLite with linked legacy records."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class MemberCacheMigrationTest(unittest.TestCase):
    def test_removed_profile_preserves_existing_related_records(self):
        source = (ROOT / "app/src/main/java/com/tridev/familyhub/data/local/FamilyHubDatabase.java").read_text()
        migration = source.split("MIGRATION_38_39 =", 1)[1].split("public abstract", 1)[0]
        sql = re.search(r'database.execSQL\("([^"]+)"\)', migration).group(1)
        db = sqlite3.connect(":memory:")
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("CREATE TABLE family_members (id INTEGER PRIMARY KEY, name TEXT NOT NULL, ownerFamilyId TEXT NOT NULL, cloudProfileId TEXT NOT NULL, syncPending INTEGER NOT NULL)")
        db.execute("INSERT INTO family_members VALUES (7, 'Existing member', 'familyA', 'profile1', 0)")
        for table in ("health_records", "vehicles", "properties"):
            db.execute(f"CREATE TABLE {table} (id INTEGER PRIMARY KEY, memberId INTEGER NOT NULL REFERENCES family_members(id) ON DELETE CASCADE, title TEXT NOT NULL)")
            db.execute(f"INSERT INTO {table} VALUES (11, 7, 'Preserve me')")
        db.execute(sql)
        self.assertEqual(db.execute("SELECT id, cloudRemoved FROM family_members").fetchall(), [(7, 0)])
        db.execute("UPDATE family_members SET cloudRemoved=1 WHERE id=7")
        self.assertEqual(db.execute("SELECT id FROM family_members WHERE cloudRemoved=0").fetchall(), [])
        for table in ("health_records", "vehicles", "properties"):
            self.assertEqual(db.execute(f"SELECT memberId, title FROM {table}").fetchall(), [(7, "Preserve me")])
        self.assertEqual(db.execute("PRAGMA foreign_key_check").fetchall(), [])
        db.execute("UPDATE family_members SET cloudRemoved=0 WHERE id=7")
        self.assertEqual(db.execute("SELECT id FROM family_members WHERE cloudRemoved=0").fetchall(), [(7,)])
        db.close()


if __name__ == "__main__":
    unittest.main()
