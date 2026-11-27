# Operator guide

During a test run you, the **operator**, act as the users of the platform under test: first as the person
who enters the transport document, then as the carrier and finally as the consignee. The **test runner**
plays the eFTI gate. It tells you in the terminal when to do each step (lines starting with `>>>`), and
after each step it checks the messages your platform sends to the gate.

You need: the platform's normal user interface (or API) and the terminal where the runner is running
(`sh tst.sh run ...`, see [GETTING_STARTED.md](../GETTING_STARTED.md)).

## Data sets

| Key | Mode | Values | When to run it |
|---|---|---|---|
| DS1 | Road | [ds1-road.md](ds1-road.md) | Profile P01 |
| DS1C | Road, cabotage | [ds1c-road-cabotage.md](ds1c-road-cabotage.md) | Optional, recommended when testing road; once requested, its findings count |
| DS1-DG | Road, dangerous goods | [ds1-road.md](ds1-road.md) | Profile P05 for road |
| DS2 | Rail | [ds2-rail.md](ds2-rail.md) | Profile P02 |
| DS2-DG | Rail, dangerous goods | [ds2-rail.md](ds2-rail.md) | Profile P05 for rail |
| DS3 | Inland waterways | [ds3-inland-waterways.md](ds3-inland-waterways.md) | Profile P03 |
| DS3-DG | Inland waterways, dangerous goods | [ds3-inland-waterways.md](ds3-inland-waterways.md) | Profile P05 for inland waterways |
| DS4 | Air | [ds4-air.md](ds4-air.md) | Profile P04 |

A dangerous goods variant can run in addition to its base data set or on its own. If your platform can import XML, you can
import `formats/<version>/testdata/<name>.xml` (e.g. `formats/ri-v0.5/testdata/ds1-road-dg.xml`) instead of typing
the values.

[datasets.yml](datasets.yml) holds what the runner needs to know about each data set. To choose the data sets of a
run, set `datasets:` in your platform's `tst.yml` (default: `[DS1]`), or name them for one run, which overrides
`tst.yml`:

```sh
sh tst.sh run myplatform DS1 DS2-DG
sh tst.sh run DS1 DS2-DG              # on the pseudo-platform
```

Run at least two data sets to also test a follow-up that mixes two data sets (T-NEG-10). Any two will do, as long as
one gets as far as the data request and the other is activated. With one data set, this test is reported as not
applicable.

## A run

The data sets run one after the other, in the order you requested. Each has the same four steps; the runner
prefixes its instructions with the key of the current data set, e.g. `[DS2]`.

Do each step only when the runner asks for it, and do the steps in this order. Between the steps the runner
sends requests to your platform and waits for the answers; you don't need to do anything then.

| # | When the runner says | What you do on the platform | What the platform must do on its own | On the pseudo-platform |
|---|---|---|---|---|
| 1 | *create the data set ... enter the data set ID* | Create a new transport document (eFTI data set) with the values of the data set, exactly as given, including the identifier of the transport means with its deliberate typo and the umlauts (don't replace them, e.g. `ö` by `oe`). Do **not** sign it as the carrier. Type the data set ID your platform shows into the runner's terminal and press Enter. | Nothing yet. The data set is a draft: the platform must refuse to hand it out to the gate (it answers the runner's request with an error). | Choose the data set in the dropdown if needed, click **Create**, and paste the ID shown into the terminal. |
| 2 | *apply the carrier signature* | Acting as the carrier who takes over the goods, sign the document. | Activate the data set and send its identifiers (transport means, dates, mode of transport, ...) to the gate. | Click the **Carrier signature** button |
| 3 | *correct the identifier of the transport means from ... to ...* | Correct the value as the runner says. Change nothing else. | Send the complete, updated identifier set to the gate. Then answer the runner's data requests and follow-up messages. | Replace the value in the data set's XML, click **Update**. |
| 4 | *apply the consignee signature* | Acting as the consignee who receives the goods, sign the document. | Send the identifiers again, now with the actual delivery date (= time of this signature). Keep answering data requests. | Click the **Consignee signature** button |

After steps 2 to 4, the runner continues by itself as soon as the platform sends the expected identifiers. An upload
with other values, or one the gate rejects, does not count: the runner prints why and keeps waiting, so that you can
correct it on the platform. The report names such uploads in a note on the step. If the expected upload does not come, press Enter in the runner's terminal once you have
done the step; if it then does not arrive within 72 s, the step fails with the
deviations of the latest upload, and the runner continues with the next data set. Without Enter, the same happens
after 15 minutes.

Before asking for the consignee signature of the first data set activated in the run, the runner sends deliberately
faulty requests for up to 72 s. Once two data sets are activated, it sends one more. You don't need to do anything
for these.

## If a run stops

If within 15 minutes no identifier upload for the step arrives and the step is not confirmed (or no data set ID is
entered in step 1), the run is stopped. The same happens if you interrupt it (Ctrl-C) or the runner's access point
stays unavailable. The report then covers the data sets that went through and names those requested but not
completed, and it cannot pass. To test those data sets, start a new run with all of them: a report covers one run.

## Driving a run from a script

For repeatable runs, a script can play the operator: it starts `sh tst.sh run ...` with standard input and output as
pipes, reads the output line by line and performs each step through your platform's interface. It can rely on the
following; all other output is meant for people and may change.

- **`>>> [<key>] Step <n> of 4: ...`** asks for step `<n>` of the table above, for data set `<key>`. The values are in
  the XML of the data set, the typo and its correction in [datasets.yml](datasets.yml) (`transportMeansTypo`,
  `transportMeansCorrected`).
- **After step 1,** write the data set ID and a line break to the standard input.
- **After steps 2 to 4,** write a line break once the step is done, to have the step evaluated within 72 s if
  the expected upload does not come. Only write it after the `>>>` line: earlier input is discarded.
- **Keep the standard input open** until the runner exits: closing it stops the run with a technical error.
- **Result:** exit code `0` if the platform passed, `1` if a test case failed, `2` on a technical error, and the
  details in `reports/<partyId>/run.json`.
