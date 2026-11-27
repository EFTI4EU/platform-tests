# Test data set DS2 (rail) and DS2-DG

## About this data set

A rail wagon pulled by a locomotive carries the goods from the freight station München to the freight station Wien. DS2 holds the subset EU01, mode of transport rail, no dangerous goods; DS2-DG adds UN 1263 paint in steel drums and the subset EU05b. The platform sets the carrier acceptance date at the carrier signature (if your platform can't, enter any date). The steps common to all data sets are in [README.md](README.md).

## Values

| Data element | Value |
|---|---|
| Carrier acceptance location - additional street | `Gleis 4` |
| Carrier acceptance location - building number | `3` |
| Carrier acceptance location - city | `München` |
| Carrier acceptance location - country code | `DE` |
| Carrier acceptance location - region | `Bayern` |
| Carrier acceptance location - postcode | `80687` |
| Carrier acceptance location - street | `TST Synthetic Gueterbahnhof-Strasse` |
| Consignee receipt location - additional street | `Gleis 2` |
| Consignee receipt location - building number | `15` |
| Consignee receipt location - city | `Wien` |
| Consignee receipt location - country code | `AT` |
| Consignee receipt location - region | `Wien` |
| Consignee receipt location - postcode | `1100` |
| Consignee receipt location - street | `Frachtenbahnhofstrasse` |
| Consignor - name (35 characters, maximum length) | `TST Synthetic Consignor & Böhm GmbH` |
| Consignor - additional street | `Gleis 4` |
| Consignor - building number | `3` |
| Consignor - city | `München` |
| Consignor - country code | `DE` |
| Consignor - region | `Bayern` |
| Consignor - department | `TST Synthetic Rail Dispatch Dept. 1` |
| Consignor - PO box | `PF 2001` |
| Consignor - postcode | `80687` |
| Consignor - street | `TST Synthetic Gueterbahnhof-Strasse` |
| Gross weight of the consignment (eFTI41) | `24000 KGM` |
| Dangerous goods indicator (eFTI1451) | `false` |
| Mode of transport (eFTI581) | `2` (rail) |
| Transport means - locomotive number (eFTI618) | `918061870132` (deliberate typo), corrected to `918061870123` |
| Transport means - registration country (eFTI620) | `DE` |
| Transport equipment - category code (eFTI378) | `RR` (rail car) |
| Transport equipment - identifier (eFTI374) | `318047110025` |
| Transport equipment - registration country (eFTI578) | `DE` |
| Transport equipment - sequence number (eFTI987) | `1` |

## Values added by DS2-DG

| Data element | Value |
|---|---|
| Dangerous goods indicator (eFTI1451) | `true` instead of `false` |
| Consignee - name (eFTI68) | `TST Synthetic Consignee Wien GmbH` |
| Consignee - building number | `15` |
| Consignee - city | `Wien` |
| Consignee - country code | `AT` |
| Consignee - postcode | `1100` |
| Consignee - street | `Frachtenbahnhofstrasse` |
| Dangerous goods packages - quantity (eFTI296) | `24` |
| Dangerous goods packages - danger label (eFTI301) | `3` |
| Dangerous goods packages - packaging type (eFTI298) | `Steel drum` |
| Dangerous goods - gross weight (eFTI238) | `840 KGM` |
| Dangerous goods - hazard type code (eFTI240) | `3` |
| Dangerous goods - packing group (eFTI236) | `II` |
| Dangerous goods - technical name (eFTI235) | `PAINT` |
| Dangerous goods - UN number (eFTI232) | `1263` |
