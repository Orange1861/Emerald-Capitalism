# Brief 3 village-naming acceptance dump

This deterministic offline dump exercises the same exported naming system used by the runtime. It uses only `villager_names.json`; it does not add or synthesize roots. Each row resolves slot 1 from the selected biome pool, applies the regional form, builds slot 2 with rule A1 and then applies village drift in ascending rule order, derives slot 3 from the regional profession byname, and appends slot 4 from the named origin village.

Rows: 42 villagers across 7 villages and 5 biomes.

| Villager name | Village | Biome | Drift rules | Slot 1 base form | Profession | Age |
|---|---|---|---|---|---|---|
| Nupiniin Flechi Se Aster Ford | Aster Ford | plains | D1 | Nupi | Fletcher | adult |
| Pennuriin Kompi Se Aster Ford | Aster Ford | plains | D1 | Pennur | Farmer | adult |
| Rilwegekek Buti Se Aster Ford | Aster Ford | plains | D1 | Rilweg | Cleric | child |
| Kentoriin Meisoni Se Aster Ford | Aster Ford | plains | D1 | Kentor | Mason | adult |
| Ruspumiin Lekti Se Aster Ford | Aster Ford | plains | D1 | Ruspum | Librarian | adult |
| Hungorekek Armini Se Aster Ford | Aster Ford | plains | D1 | Hungor | Armorer | child |
| Kenhiniin Flechi Se Copper Reach | Copper Reach | savanna | D1, D10 | Kenhin | Fletcher | adult |
| Heltuliin Kompi Se Copper Reach | Copper Reach | savanna | D1, D10 | Heltul | Farmer | adult |
| Sospinekek Puti Se Copper Reach | Copper Reach | savanna | D1, D10 | Sospi | Cleric | child |
| Lergoriin Meisoni Se Copper Reach | Copper Reach | savanna | D1, D10 | Lergor | Mason | adult |
| Olmigiin Lekti Se Copper Reach | Copper Reach | savanna | D1, D10 | Olmig | Librarian | adult |
| Helnekekek Armini Se Copper Reach | Copper Reach | savanna | D1, D10 | Helnek | Armorer | child |
| Worgorinin Flichi Se Glasswind | Glasswind | desert | D5, D12 | Worgor | Fletcher | adult |
| Soslukinin Kowpi Se Glasswind | Glasswind | desert | D5, D12 | Sosluk | Farmer | adult |
| Worwunekek Buti Se Glasswind | Glasswind | desert | D5, D12 | Wormun | Cleric | child |
| Biwpolinin Meisoni Se Glasswind | Glasswind | desert | D5, D12 | Bempol | Mason | adult |
| Hiltorinin Likti Se Glasswind | Glasswind | desert | D5, D12 | Heltor | Librarian | adult |
| Kirtorekek Arwini Se Glasswind | Glasswind | desert | D5, D12 | Kirtor | Armorer | child |
| Rusnehinin Flechi Se Pine Ledger | Pine Ledger | taiga | D8 | Rusnek | Fletcher | adult |
| Gisluhinin Kumpi Se Pine Ledger | Pine Ledger | taiga | D8 | Gisluk | Farmer | adult |
| Winehekek Buti Se Pine Ledger | Pine Ledger | taiga | D8 | Winek | Cleric | child |
| Kirsibinin Meisuni Se Pine Ledger | Pine Ledger | taiga | D8 | Kirsib | Mason | adult |
| Rusluhinin Lehti Se Pine Ledger | Pine Ledger | taiga | D8 | Rusluk | Librarian | adult |
| Hunpumekek Armini Se Pine Ledger | Pine Ledger | taiga | D8 | Hunpum | Armorer | child |
| Senneninin Fleni Se White Step | White Step | snowy | D8 | Sennek | Fletcher | adult |
| Kenneninin Komi Se White Step | White Step | snowy | D8 | Kennek | Farmer | adult |
| Kirnenekek Buni Se White Step | White Step | snowy | D8 | Kirnek | Cleric | child |
| Hunpininin Meisoni Se White Step | White Step | snowy | D8 | Hunpi | Mason | adult |
| Mudininin Lehti Se White Step | White Step | snowy | D8 | Mudin | Librarian | adult |
| Sendinekek Armini Se White Step | White Step | snowy | D8 | Sendin | Armorer | child |
| Sosbirinin Flechi Se Juniper Fold | Juniper Fold | plains | D10, D11 | Sosbir | Fletcher | adult |
| Penrolinin Kompi Se Juniper Fold | Juniper Fold | plains | D10, D11 | Penrol | Farmer | adult |
| Worsunekek Pusi Se Juniper Fold | Juniper Fold | plains | D10, D11 | Worsu | Cleric | child |
| Kenpuminin Meisoni Se Juniper Fold | Juniper Fold | plains | D10, D11 | Kenpum | Mason | adult |
| Nurolinin Leksi Se Juniper Fold | Juniper Fold | plains | D10, D11 | Nurol | Librarian | adult |
| Kennurekek Armini Se Juniper Fold | Juniper Fold | plains | D10, D11 | Kennur | Armorer | child |
| Worpolenin Fleche Se Sunken Toll | Sunken Toll | savanna | D3 | Worpol | Fletcher | adult |
| Lomrolenin Kompe Se Sunken Toll | Sunken Toll | savanna | D3 | Lomrol | Farmer | adult |
| Nulukekek Bute Se Sunken Toll | Sunken Toll | savanna | D3 | Nuluk | Cleric | child |
| Senhenenin Meesone Se Sunken Toll | Sunken Toll | savanna | D3 | Senhin | Mason | adult |
| Oltulenin Lekte Se Sunken Toll | Sunken Toll | savanna | D3 | Oltul | Librarian | adult |
| Worsunekek Armene Se Sunken Toll | Sunken Toll | savanna | D3 | Worsu | Armorer | child |

Acceptance notes:

- Exactly one or two drift IDs are selected per village; D9 and D10 are mutually exclusive, and selected rules are applied in ascending rule index.
- Slot 1 is a substrate pair and is not replaced during ordinary refreshes; the runtime reserves pairs until the village pool is exhausted.
- Slot 2 uses A1 hiatus repair: vowel-final stems take inserted n before -a, -i, -o, -in, -ek or -ur.
- Slot 4 renders as `Se [origin village]` after the persisted origin village has a determined name. It is not passed through drift.
- Special first names are manually maintained in the converter's `SPECIAL_FIRST_NAMES` section, selected at 1 in 10,000 assignments, and replace only slot 1.
- The elder suffix is exported, but Minecraft 1.21.1 exposes only child/adult villager age states; no elder transition was invented, so that gap is reported for a later age-state seam.
