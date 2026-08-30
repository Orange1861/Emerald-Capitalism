# Village Naming Acceptance Dump

This dump is produced by `VillageNamingAcceptanceEvidenceTest` from the regenerated production lexicon. It exercises five biome contexts, applies C1 seam repairs without the place-name clip, and records both the concatenated and repaired forms.

The current canonical resource contains 231 roots, 209 enabled for village naming, and the report confirms that the Rule C1 compound check ran.

| village name | biome | concatenation | seam-repaired | root 1 | root 2 |
|---|---|---|---|---|---|
| Pitsalma | plains | Pitsalma | Pitsalma | pit | salma |
| Krupbid | desert | Krupbid | Krupbid | krup | bid |
| Krupkeit | savanna | Krupkeit | Krupkeit | krup | keit |
| Krupbeil | taiga | Krupbeil | Krupbeil | krup | beil |
| Krubluu | snowy | Krubluu | Krubluu | krup | bluum |
| Pitpaki | plains | Pitpaki | Pitpaki | pit | paki |
| Krupai | desert | Krupai | Krupai | krup | ai |
| Krupai | savanna | Krupai | Krupai | krup | ai |
| Krupeiha | taiga | Krupeiha | Krupeiha | krup | eiha |
| Kruki | snowy | Kruki | Kruki | krup | kil |

The 20-case deterministic sample had a 0.00% reroll rate (0/20). Focused seam tests cover `emra + hah`, `polapa + komp`, and `shipwrek + krist`, including the no-clip and geminate-repair behavior.

Drift IDs are persisted at village founding and normalized to ascending rule index across codec round trips.
