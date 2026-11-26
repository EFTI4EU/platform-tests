# Test data set DS1C (road cabotage)

## About this data set

A Polish carrier performs a domestic carriage within Germany: a cabotage operation. The data set holds the subsets EU01 and EU03, mode of transport road, no dangerous goods. The platform sets the carrier acceptance date at the carrier signature (if your platform can't, enter any date). It also sets the statement code of the consignor signature (eFTI65), so this value is not listed below and not compared. The steps common to all data sets are in [README.md](README.md).

## Values

| Data element | Value |
|---|---|
| Carrier - name (eFTI87) | `TST Synthetic Carrier Sp. z o.o.` |
| Carrier - additional street | `Hala B` |
| Carrier - building number | `12` |
| Carrier - city | `Poznan` |
| Carrier - country code | `PL` |
| Carrier - region | `Wielkopolskie` |
| Carrier - PO box | `PO 101` |
| Carrier - postcode | `61-001` |
| Carrier - street | `ul. Testowa` |
| Carrier acceptance location - additional street | `Gate 3` |
| Carrier acceptance location - building number | `7` |
| Carrier acceptance location - city | `Leipzig` |
| Carrier acceptance location - country code | `DE` |
| Carrier acceptance location - region | `Sachsen` |
| Carrier acceptance location - postcode | `04109` |
| Carrier acceptance location - street | `Musterstrasse` |
| Consignee - name (eFTI68) | `TST Synthetic Consignee Handel GmbH` |
| Consignee - additional street | `Halle 2` |
| Consignee - building number | `42` |
| Consignee - city | `München` |
| Consignee - country code | `DE` |
| Consignee - region | `Bayern` |
| Consignee - PO box | `PF 2042` |
| Consignee - postcode | `80331` |
| Consignee - street | `Beispielweg` |
| Consignee receipt location - additional street | `Rampe 5` |
| Consignee receipt location - building number | `42` |
| Consignee receipt location - city | `München` |
| Consignee receipt location - country code | `DE` |
| Consignee receipt location - region | `Bayern` |
| Consignee receipt location - postcode | `80331` |
| Consignee receipt location - street | `Beispielweg` |
| Consignor - name (35 characters, maximum length) | `TST Synthetic Consignor & Söhne OHG` |
| Consignor - additional street | `Gate 3` |
| Consignor - building number | `7` |
| Consignor - city | `Leipzig` |
| Consignor - country code | `DE` |
| Consignor - region | `Sachsen` |
| Consignor - department | `Shipping department` |
| Consignor - PO box | `PF 1007` |
| Consignor - postcode | `04109` |
| Consignor - street | `Musterstrasse` |
| Gross weight of the consignment (eFTI41) | `12500 KGM` |
| Dangerous goods indicator (eFTI1451) | `false` |
| Mode of transport (eFTI581) | `3` (road) |
| Transport means - registration plate (eFTI618) | `BAB132` (deliberate typo), corrected to `BAB123` |
| Transport means - registration country (eFTI620) | `PL` |
| Transport means - type code (eFTI617) | `31` (truck) |
| Goods description (eFTI699) | `Machine parts on 24 euro pallets: steel flanges, gaskets and ball bearings, packed in cardboard boxes, stretch-wrapped. Free-text element filled to its maximum length of 512 characters for the round-trip test, padded with digits: 012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901` |
| Packages - quantity (eFTI685) | `24` |
| Packages - marking text (eFTI691) | `TST-MARK-0024` |
| Packages - type code (eFTI688) | `PX` (pallet) |
| Transport equipment - category code (eFTI378) | `TE` (trailer) |
| Transport equipment - identifier (eFTI374) | `PO4711T` |
| Transport equipment - registration country (eFTI578) | `PL` |
| Transport equipment - sequence number (eFTI987) | `1` |
