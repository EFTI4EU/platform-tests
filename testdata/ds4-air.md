# Test data set DS4 (air)

## About this data set

A regulated agent issues the security status of an air cargo consignment after X-ray screening; the consignment flies on an aircraft identified by its registration. The data set holds the subset EU06, mode of transport air, no dangerous goods. The platform sets the carrier acceptance date at the carrier signature (if your platform can't, enter any date). The steps common to all data sets are in [README.md](README.md).

## Values

| Data element | Value |
|---|---|
| Security status code (eFTI844) | `SPX` (secure for passenger, all-cargo and all-mail aircraft) |
| Risk analysis level code (eFTI846) | `RA` (regulated agent) |
| Screening method code (eFTI848) | `XRY` (X-ray equipment) |
| Dangerous goods indicator (eFTI1451) | `false` |
| Mode of transport (eFTI581) | `4` (air) |
| Transport means - aircraft registration (eFTI618) | `D-ASTT` (deliberate typo), corrected to `D-ATST` |
| Transport means - registration country (eFTI620) | `DE` |
| Goods description (eFTI699) | `Machine spare parts in cardboard boxes, air cargo. Free-text element filled to its maximum length of 512 characters for the round-trip test, padded with digits: 012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890` |
| Transport document - identifier (eFTI170) | `999-47110012` |
| Transport document - type code (eFTI169) | `741` (master air waybill) |
| Security status - name of the person issuing it (eFTI200) | `TST Synthetic Security Officer Jörg` |
| Security status - date/time of issue (eFTI218) | `202609150730` (format `203`) |
| Security status - identifier of the regulated agent (eFTI195) | `DE/RA/04711-01` |
