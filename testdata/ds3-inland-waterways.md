# Test data set DS3 (inland waterways) and DS3-DG

## About this data set

A pushed dry cargo barge without engine carries the goods from the port of Duisburg to the port of Rotterdam. DS3 holds the subset EU01, mode of transport inland waterway, no dangerous goods; DS3-DG adds UN 1263 paint in steel drums and the subset EU05c. The platform sets the carrier acceptance date at the carrier signature (if your platform can't, enter any date). The steps common to all data sets are in [README.md](README.md).

## Values

| Data element | Value |
|---|---|
| Carrier acceptance location - additional street | `Kai 12` |
| Carrier acceptance location - building number | `1` |
| Carrier acceptance location - city | `Duisburg` |
| Carrier acceptance location - country code | `DE` |
| Carrier acceptance location - region | `Nordrhein-Westfalen` |
| Carrier acceptance location - postcode | `47119` |
| Carrier acceptance location - street | `TST Synthetic Hafenkai Ruhrort-Süd` |
| Consignee receipt location - additional street | `Kade 7` |
| Consignee receipt location - building number | `30` |
| Consignee receipt location - city | `Rotterdam` |
| Consignee receipt location - country code | `NL` |
| Consignee receipt location - region | `Zuid-Holland` |
| Consignee receipt location - postcode | `3011 AB` |
| Consignee receipt location - street | `Synthetische Havenstraat` |
| Consignor - name (35 characters, maximum length) | `TST Synthetic Consignor & Kähne OHG` |
| Consignor - additional street | `Kai 12` |
| Consignor - building number | `1` |
| Consignor - city | `Duisburg` |
| Consignor - country code | `DE` |
| Consignor - region | `Nordrhein-Westfalen` |
| Consignor - department | `TST Synthetic Port Shipping Dept. 1` |
| Consignor - PO box | `PF 3001` |
| Consignor - postcode | `47119` |
| Consignor - street | `TST Synthetic Hafenkai Ruhrort-Süd` |
| Gross weight of the consignment (eFTI41) | `850000 KGM` |
| Dangerous goods indicator (eFTI1451) | `false` |
| Mode of transport (eFTI581) | `8` (inland waterway) |
| Transport means - ENI vessel number (eFTI618) | `04807132` (deliberate typo), corrected to `04807123` |
| Transport means - registration country (eFTI620) | `DE` |

## Values added by DS3-DG

| Data element | Value |
|---|---|
| Dangerous goods indicator (eFTI1451) | `true` instead of `false` |
| Consignee - name (eFTI68) | `TST Synthetic Consignee Haven BV` |
| Consignee - building number | `30` |
| Consignee - city | `Rotterdam` |
| Consignee - country code | `NL` |
| Consignee - postcode | `3011 AB` |
| Consignee - street | `Synthetische Havenstraat` |
| Dangerous goods packages - quantity (eFTI296) | `12` |
| Dangerous goods packages - danger label (eFTI301) | `3` |
| Dangerous goods packages - packaging type (eFTI298) | `Steel drum` |
| Dangerous goods - gross weight (eFTI238) | `420 KGM` |
| Dangerous goods - packing group (eFTI236) | `II` |
| Dangerous goods - proper shipping name (eFTI251) | `PAINT` |
| Dangerous goods - technical name (eFTI235) | `PAINT` |
| Dangerous goods - UN number (eFTI232) | `1263` |
