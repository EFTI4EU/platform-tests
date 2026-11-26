# Getting started

This guide takes you from zero to a test report in about 15 minutes. You will:

1. start the local **test setup** (an eDelivery access point plus the test runner, all in Docker),
2. do a **demo run** against the built-in pseudo-platform, so you see what a run looks like,
3. connect **your own platform** and test it.

Background on how the suite works: [README.md](README.md). Unfamiliar terms: see the [glossary](#glossary) at
the end.

## What you need

- **Docker** with the compose plugin (Docker Desktop, OrbStack or Docker Engine). Nothing else: no Java, no
  Domibus knowledge.
- About **4 GB of free memory** for Docker and free ports **18080** (access point of the test runner) and **18090** (pseudo-platform).
- A terminal in the folder `deploy/` of this repository. All commands below are run from there.
- **Windows:** use WSL 2 (clone the repository and run the commands in the WSL terminal, with Docker Desktop's
  WSL integration enabled). Preferably clone into the WSL file system (e.g. your WSL home folder): a Windows
  drive (`/mnt/c/...`) works too, but Docker is slower there.

Check Docker works: `docker info` must print information and no error.

## Step 1: Start the test setup

```sh
cd deploy
sh tst.sh start
```

The first start builds the images and takes several minutes; later starts take about a minute. It is done
when it prints the URLs:

- `http://localhost:18090` - the pseudo-platform (a demo eFTI platform with a web page)
- `http://localhost:18080/domibus/` - admin console of the runner's access point (user `super`, password
  `123456`); you only need it for troubleshooting

`sh tst.sh status` shows what is running at any time. `sh tst.sh help` lists all commands.

## Step 2: Demo run with the pseudo-platform

In a run, two parties work together:

- **the test runner** (in your terminal) plays the gate: it tells you what to do, sends requests to the
  platform and checks every answer;
- **you, the operator**, play the users of the platform (the carrier, the consignee) and do four things on
  the platform's user interface when the runner asks for them. The [operator guide](testdata/README.md) describes
  them.

1. Open `http://localhost:18090` in your browser. You see the pseudo-platform with an XML text box that is
   already filled with the test data set DS1 (the dropdown above it loads the others).
2. In the terminal, start the run:

   ```sh
   sh tst.sh run
   ```

3. Follow the runner and do the four steps as the [operator guide](testdata/README.md#a-run) describes them (see
   its column *On the pseudo-platform*). Each instruction starts with `>>>`. Don't do a step before the runner asks
   for it; the runner checks the platform's state at each point. You can watch every message on the
   pseudo-platform page, below its data set (refreshes itself).

4. At the end the runner prints `Overall result: PASSED` (or `FAILED`) and the path of the report.

## Step 3: Read the report

Open `reports/pseudoplatform/report.html` (in `deploy/`) in your browser.

- **Top:** platform, gate identifier, run time, overall result, number of passed / failed / not applicable test cases.
- **Section 1.1:** all data sets, whether they were requested, and the result of each (its tests in sections 2.1 and
  2.2; a run can fail on tests of sections 2.3 and 2.4 alone).
- **Section 2.1 to 2.4:** one row per test case (ID and title) with its result per data set. For a failed test
  case, the findings below it say exactly what was wrong and in which message. What each test case checks and its
  legal references are in the test case description (`docs/testcase-description.md`).
- **Appendix:** every message exchanged in the run (click to expand the XML).

`reports/pseudoplatform/run.json` contains the same in machine-readable form.

**See tests fail:** the pseudo-platform has three fault switches at the top of its page (*Fault toggles*) (never answer data
requests; ignore the subset filter; forget the identifier upload after an edit). Tick one, click **Save**,
and run `sh tst.sh run` again. With the last one, the upload after the edit (step 3) never comes: press Enter in
the runner's terminal after the edit, and the runner fails T-RUN-03 72 s later (without Enter, only after 15
minutes).
To start a run on a clean page, click **Clear data sets and messages** at the top (the fault toggles are kept).

## Step 4: Test your own platform

### What your platform needs

- An **eDelivery AS4 access point** (e.g. Domibus) that you can configure, reachable from Docker on your
  machine (or from the machine where you run the setup).
- Its **certificate** (public part, PEM file) used for signing and encryption.
- Support for the **message format** the test runner speaks: the eDelivery payloads of the eFTI reference
  implementation, see `formats/ri-v0.5/xsd/edelivery/` and the example messages in `formats/ri-v0.5/templates/`.
  Two rules of the eFTI technical guidance that the reference implementation does not follow: every response
  sets the AS4 **RefToMessageId** to the AS4 message ID of the request it answers, and a follow-up acknowledgement
  echoes the request ID of the *data request* the follow-up refers to (`uilQueryRequestId`), not the follow-up's own.
- A user interface (or API) where an operator can create a data set from given values, apply the carrier
  and consignee signatures and edit the identifier of the transport means.

### Connect your access point

If your access point cannot be reached from the runner's access point (e.g. behind NAT), or you don't have one yet, see
["Hosting your access point in the test setup" in the deploy Readme](deploy/README.md#hosting-your-access-point-in-the-test-setup).

```sh
sh tst.sh onboard myplatform http://host.docker.internal:8080/domibus/services/msh ./myplatform-cert.pem
```

- `myplatform`: the AS4 party ID of your access point (lowercase letters, digits, `.`, `_`, `-`).
- The endpoint: the AS4 address of your access point **as seen from inside Docker**, `http://` only (TLS is
  not supported yet). If your access point runs on the same machine, use `host.docker.internal` instead of
  `localhost`. If it runs on another machine, start the setup with `TST_BIND=0.0.0.0 sh tst.sh start` so that
  it can reach the runner's access point. **Only do this on a network you trust:** everyone who can reach your machine can then
  use the admin console and WS plugin of that access point with the test logins from this guide.
  If your access point runs in Docker too, it can be easier to put both on one Docker network, so that they reach
  each other by service name: either your containers join the setup's network `tst_default` (as an `external`
  network in your compose file; start the test setup first), or you add your network to the `domibus` service in
  `docker-compose.yml`. The endpoint is then `http://<your service>:<port>/...` and that of the runner's access point is
  `http://domibus:8080/domibus/services/msh?domain=tstgate`. No service name (e.g. `domibus`, `mysql`) may occur
  twice on a shared network.
- The certificate file of your access point.
- Optional fourth argument: your platform ID as used in UILs, if it differs from the party ID.

This registers your access point with the runner's access point (truststore and PMode) and creates `platforms/myplatform/`:

| File | Use |
|---|---|
| `connection-info.txt` | **Everything you need to configure your access point**: the party ID and endpoint of the runner's access point, service, action, party ID type, message properties, security policy. |
| `tstgate.pem` | The certificate of the runner's access point, generated for this installation; import it into your access point's truststore. |
| `tst.yml` | The runner configuration for your platform. Fill in `report.platformVersion` and `report.company`, and list the data sets to run under `datasets:`. |

Configure your access point with these values. Your platform sends its messages to the party `tstgate`,
i.e. the access point of the test runner, and receives the runner's requests from it.

If your access point is a Domibus:

- **PMode:** you can start from `deploy/setup/pmode-template.xml`. Replace `SELF_PARTY` with your party ID, replace the
  party `pseudoplatform` with your own (its `<party>` with your endpoint, its `<initiatorParty>` and its
  `<responderParty>`) and set the endpoint of `tstgate` to the one in `connection-info.txt`.
- **Aliases:** Domibus looks up a certificate by the party's name in the PMode, so import `tstgate.pem` into the
  truststore with the alias `tstgate`. For your own key, best use your party ID as alias too
  (`domibus.security.key.private.alias`).

### Run the tests

```sh
sh tst.sh run myplatform
```

This runs the data sets listed in `tst.yml`; `sh tst.sh run myplatform DS1 DS2` overrides them for one run. Do
the four operator steps per data set when the runner asks for them, as the
[operator guide](testdata/README.md) describes, which also lists the data sets and the file with the values of each.
The report is written to `reports/myplatform/report.html`. Each run overwrites the report of the previous one.

To remove your platform again: `sh tst.sh offboard myplatform`.

## Troubleshooting

| Problem | What to do |
|---|---|
| Something fails and you don't know why | Look at the logs: `sh tst.sh logs domibus`, `sh tst.sh logs pseudo-platform`, `sh tst.sh logs domibus-setup` (Ctrl-C to stop following). |
| `start` fails with "port is already allocated" | Another program uses port 18080 or 18090. Stop it, or choose other ports for all commands, e.g. `export TST_GATE_PORT=28080 TST_PLATFORM_PORT=28090` before `sh tst.sh start`. |
| The first `start` fails while building with a timeout for `code.europa.eu:4567` (e.g. "timeout awaiting response headers") | The EU registry that hosts the official Domibus image is often slow. Run `sh tst.sh start` again, several times if needed; once the image is downloaded, later starts do not need the registry. If it never works, run `curl -I https://code.europa.eu:4567/v2/`: a quick `401` is the expected answer and means the registry is reachable, so try again later. No answer means your network blocks port 4567 for Docker (firewall or proxy). |
| `start` is very slow or Domibus restarts repeatedly | Give Docker more memory (at least 4 GB). If that does not help, raise the `mem_limit` of the service in `docker-compose.yml`. |
| The runner waits for an identifier upload that never comes | Your platform did not send it, or the message did not arrive. In the admin console (`http://localhost:18080/domibus/`, domain **tstgate**, *Messages*) you see every message the runner's access point received and why a message failed. Check your access point's logs too. |
| The runner's data requests are not answered | In the admin console (domain **tstgate**, *Messages*) check whether the outgoing message to your platform has the status *SEND_FAILURE*: then the runner's access point cannot reach your endpoint (wrong URL, `localhost` instead of `host.docker.internal`, or your access point does not trust `tstgate.pem`). |
| Domibus is in a broken state (e.g. after an interrupted setup) | `sh tst.sh reset` deletes all messages and Domibus state and starts fresh (onboarded platforms and reports are kept). Not needed between runs: the runner discards messages left over from earlier runs itself. |
| The pseudo-platform page shows an error | `sh tst.sh logs pseudo-platform`; restarting it (`docker compose restart pseudo-platform`) clears its data sets. |

## Stop

```sh
sh tst.sh stop     # done for now: stop everything, keep data (continue later with sh tst.sh start)
sh tst.sh reset    # only if Domibus is broken: delete all messages and Domibus state, start fresh
docker compose --profile runner down -v --rmi all   # remove everything incl. the images
```

## Glossary

| Term | Meaning |
|---|---|
| eFTI gate | The national system through which authorities request transport data from eFTI platforms. Here played by the test runner. |
| Access point (AP), eDelivery, AS4 | The standard secure messaging channel between gate and platform. Each side runs an access point (often Domibus); messages are signed and encrypted. |
| Party ID | Name of an access point in the AS4 network (here: `tstgate` for the access point of the test runner, which plays the gate, `pseudoplatform`, or yours). |
| PMode | Domibus configuration that defines which parties exchange which messages how. That of the runner's access point is handled for you by `tst.sh`. |
| Data set ID, UIL | The platform gives every data set a UUID (data set ID). The UIL (unique identifying link) = gate ID + platform ID + data set ID; authorities request data by UIL. |
| Identifier upload | Message from platform to gate registering a data set's identifiers (e.g. registration plate) so authorities can find it. |
| Subset | Group of data elements authorities may request (e.g. EU01, EU03); a response must contain only the requested subsets. |
| Operator | The person performing the platform-side steps of the operator script during a run. |
