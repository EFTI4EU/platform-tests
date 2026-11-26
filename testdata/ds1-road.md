# Test data set DS1 (road) and DS1-DG

## About this data set

A swap body on a semi-trailer is carried by road from the consignor in Leipzig to a rail terminal, from where it continues by rail to Verona: the road leg of a combined transport. DS1 holds the subsets EU01 and EU02, mode of transport road, no dangerous goods; DS1-DG adds UN 1263 paint in steel drums and the subset EU05a. The platform sets the carrier acceptance date at the carrier signature (if your platform can't, enter any date). It also sets the statement code of the stamp at unloading (eFTI608), so this value is not listed below and not compared. The steps common to all data sets are in [README.md](README.md).

## Values

| Data element | Value |
|---|---|
| Carrier acceptance location - additional street | `Gate 3` |
| Carrier acceptance location - building number | `7` |
| Carrier acceptance location - city | `Leipzig` |
| Carrier acceptance location - country code | `DE` |
| Carrier acceptance location - region | `Sachsen` |
| Carrier acceptance location - postcode | `04109` |
| Carrier acceptance location - street | `TST Synthetic Logistikpark Südring` |
| Consignee receipt location - additional street | `Rampa 5` |
| Consignee receipt location - building number | `42` |
| Consignee receipt location - city | `Verona` |
| Consignee receipt location - country code | `IT` |
| Consignee receipt location - region | `Veneto` |
| Consignee receipt location - postcode | `37135` |
| Consignee receipt location - street | `Via Sintetica` |
| Consignor - name (35 characters, maximum length) | `TST Synthetic Consignor & Söhne OHG` |
| Consignor - additional street | `Gate 3` |
| Consignor - building number | `7` |
| Consignor - city | `Leipzig` |
| Consignor - country code | `DE` |
| Consignor - region | `Sachsen` |
| Consignor - department | `TST Synthetic Shipping Department 1` |
| Consignor - PO box | `PF 1007` |
| Consignor - postcode | `04109` |
| Consignor - street | `TST Synthetic Logistikpark Südring` |
| Gross weight of the consignment (eFTI41) | `12500 KGM` |
| Dangerous goods indicator (eFTI1451) | `false` |
| Mode of transport (eFTI581) | `3` (road) |
| Sequence number of the transport movement (eFTI1022) | `1` |
| Unloading - name of the certifying (stamping) party (eFTI605) | `TST Synthetic Rail Authority Leipzig` |
| Unloading - role code of the certifying party (eFTI606) | `T1` (rail authority) |
| Unloading - location name (eFTI602) | `TST Synthetic KV-Terminal Leipzig` |
| Unloading - scheduled date (eFTI598) | `20260915` (format `102`) |
| Transport means - registration plate (eFTI618) | `BAB132` (deliberate typo), corrected to `BAB123` |
| Transport means - registration country (eFTI620) | `DE` |
| Transport document - type code (eFTI169) | `730` (road consignment note) |
| Carried equipment - category code (eFTI450) | `SW` (swap body) |
| Carried equipment - identifier (eFTI448) | `TSTA4711230` |
| Carried equipment - sequence number (eFTI1000) | `1` |
| Transport equipment - category code (eFTI378) | `SM` (semi-trailer) |
| Transport equipment - identifier (eFTI374) | `LTC4711T` |
| Transport equipment - registration country (eFTI578) | `DE` |
| Transport equipment - sequence number (eFTI987) | `1` |

## Values added by DS1-DG

| Data element | Value |
|---|---|
| Dangerous goods indicator (eFTI1451) | `true` instead of `false` |
| Consignee - name (eFTI68) | `TST Synthetic Consignee Logistica` |
| Consignee - building number | `42` |
| Consignee - city | `Verona` |
| Consignee - country code | `IT` |
| Consignee - postcode | `37135` |
| Consignee - street | `Via Sintetica` |
| Dangerous goods packages - quantity (eFTI296) | `12` |
| Dangerous goods packages - danger label (eFTI301) | `3` |
| Dangerous goods packages - packaging type (eFTI298) | `Steel drum` |
| Dangerous goods - gross weight (eFTI238) | `420 KGM` |
| Dangerous goods - packing group (eFTI236) | `II` |
| Dangerous goods - tunnel restriction code (eFTI255) | `D/E` |
| Dangerous goods - UN number (eFTI232) | `1263` |
