# Family Hub Hero Quality Audit

Audit baseline: Family Live and Grocery are the reference Hero modules.

## Hero quality standard

A module is considered Hero-ready only when it has:

1. complete create, read, update and safe-delete flows;
2. responsive Fluent-style cards, forms and empty states;
3. search/filtering and direct navigation where relevant;
4. offline-first storage with explicit, privacy-safe sync behaviour;
5. loading, empty, error and retry handling;
6. notification/background integration where the feature needs it;
7. security controls appropriate to the data;
8. automated policy or regression tests for critical logic.

## Current module assessment

| Module | Level | Strong areas | Remaining gap |
|---|---|---|---|
| Family Live | Hero | Realtime family map, precision sessions, offline queue, recovery, Safe Places, SOS, journey and reports | Device matrix and long-duration field testing |
| Grocery | Hero | Shared realtime lists, Daily/Monthly flow, assignment, floating quick add, widget, recurring items, price and Finance link | Notification delivery and overlay tests across OEM devices |
| Dashboard + Global Search | Advanced | Live summaries, priority actions, direct navigation, search filters and diagnostics entry | Broader automated UI/navigation tests |
| Documents | Advanced | Secure picker, device lock, expiry policy, alerts and protected storage | Optional encrypted family sharing with explicit consent |
| Password Vault | Advanced | AES-GCM/Keystore encryption, biometric gate, secure-screen flag, CSV import/export | Biometric-gated export and recovery UX testing |
| Family/Households | Advanced | Roles, approval flow, membership validation and profile management | More role-permission regression tests |
| Planner + Reminders | Core+ | Complete CRUD, schedules, filters and notifications | Optional family assignment/realtime collaboration |
| Health | Core+ | Member-linked records, filters, summary and full forms | Consent-based sharing, attachment/history depth and tests |
| Vehicle | Core+ | Owner-linked profiles, due dates, filtering and alerts-ready model | Document linking, recurring maintenance and tests |
| Property | Core+ | Owner-linked records, valuation summary, search and forms | Document linking, timeline and tests |
| Finance | Core | Income/expense CRUD, current-month summary and Grocery expense link | Budgets, reports, account model and opt-in family sharing |
| Notes | Core | Notes/checklists, pin/archive/search | Reminders, attachments and opt-in collaboration |

## Security decision

Sensitive modules must not be uploaded merely to imitate Grocery sync. Passwords stay Keystore-encrypted and local. Health, Documents and Finance require an explicit consent/role design before any family-cloud sync is added. Family Live and Grocery already use membership-scoped Firebase rules.

## Changes made during this audit

- Applied the shared three-tone professional form background to all standard app dialogs.
- Preserved the special Family Map action surface because it has its own map-specific contrast system.
- Removed hardcoded Health and Vehicle summary/filter labels and moved them to string resources.
- Parsed all Android XML resources and checked project diffs for whitespace errors.

## Next implementation order

1. Finance 2.0: accounts, budgets, reports and safe Grocery reconciliation.
2. Planner/Reminders collaboration: family assignment, status and optional realtime sync.
3. Health/Vehicle/Property document linking and timelines.
4. Notes attachments, reminders and collaboration.
5. Full UI/device tests and release hardening.

This order closes the largest functional gaps without weakening privacy or destabilising the two Hero modules.
