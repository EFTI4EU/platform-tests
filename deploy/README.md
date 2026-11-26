# Local test setup (technical reference)

To *use* the setup, follow [GETTING_STARTED.md](../GETTING_STARTED.md); `sh tst.sh help` lists all commands.
This page explains what is in this folder and how it works, for when you need to debug or change it.

## What runs

| Service | Purpose | Host port |
|---|---|---|
| `mysql` | Database of Domibus | - |
| `domibus` | Domibus 5.2.1.4 eDelivery access point, multi-tenant with two domains (see below) | 18080 (`TST_GATE_PORT`) |
| `domibus-setup` | One-shot job on every start: uploads the PModes and adds the certificates of onboarded platforms | - |
| `pseudo-platform` | Demo eFTI platform with a web UI, connected to the `pseudoplatform` domain | 18090 (`TST_PLATFORM_PORT`) |
| `runner` | The test runner (acts as the gate); compose profile `runner`, only started by `sh tst.sh run` | - |

The two Domibus domains exchange messages over real AS4 (signed and encrypted), just like two separate
access points would:

| Domain = party ID | Role | WS plugin user (test only) |
|---|---|---|
| `tstgate` | the access point of the test runner, which plays the gate | `tstgate_ws` / `test123` |
| `pseudoplatform` | the pseudo-platform's access point | `pseudoplatform_ws` / `test123` |

An onboarded platform's own access point becomes a third party in the `tstgate` domain's PMode.

The runner and the pseudo-platform use the Domibus **WS plugin** (web service interface for submitting and
fetching messages) **in pull mode** (`submitMessage`, `listPendingMessages`, `retrieveMessage`), so they need
no inbound endpoint. If your platform requires the plugin running in push mode, you can add the following lines to 
`domibus/conf/domains/<partyId>/<partyId>-domibus.properties` and restart the Domibus container:

```
myplatform.wsplugin.push.enabled=true
myplatform.wsplugin.push.rules.all=rule for all
myplatform.wsplugin.push.rules.all.recipient=
myplatform.wsplugin.push.rules.all.endpoint=http://[PLATFORM_ADDRESS]:[PLATFORM_PORT]/ws/notification
myplatform.wsplugin.push.rules.all.retry=1;4;CONSTANT
myplatform.wsplugin.push.rules.all.type=SEND_SUCCESS,SEND_FAILURE,SUBMIT_MESSAGE
```

[PLATFORM_ADDRESS]:[PLATFORM_PORT] is the address of your platform's endpoint that receives the notifications as from within the Domibus container.

The plugin users are administrators which means that the four-corner-topology feature 
is disabled for consumers and producers attached via webservice plugin. Only one client may pull a
domain's messages at a time: don't run a second pseudo-platform (or the Domibus smoke test) while the
`pseudo-platform` service is running.

## URLs and credentials (all test-only)

The ports listen on `127.0.0.1` only. `TST_BIND=0.0.0.0` (for an access point on another machine) opens the Domibus
port to the network, including the admin console and WS plugin with the credentials below, so only use it on a trusted
network. On Linux, Docker's published ports bypass firewall rules such as `ufw`. The pseudo-platform UI always stays on
`127.0.0.1`; to open it from another machine, use a tunnel: `ssh -N -L 18090:localhost:18090 <server>`.

- Admin console: `http://localhost:18080/domibus/` - user `super`, password `123456`; choose the domain at the
  top to see its messages and PMode.
- WS plugin: `http://localhost:18080/domibus/services/wsplugin` (inside the compose network
  `http://domibus:8080/...`); the plugin user selects the domain.
- AS4 endpoint of the runner's access point: `http://<host>:18080/domibus/services/msh?domain=tstgate` (inside the network:
  `http://domibus:8080/...`).

## Files in this folder

| Path | Content |
|---|---|
| `tst.sh` | Entry script (start, stop, reset, status, logs, run, onboard, offboard). |
| `run-all.py` | Runs the test suite against the pseudo-platform without a human, see [below](#running-all-tests-without-an-operator). |
| `docker-compose.yml` | The services above. |
| `domibus/Dockerfile` | Builds a native image from the official Domibus distribution (the official image needs an x86-64-v3 CPU and does not run on Apple Silicon, not even emulated). |
| `domibus/conf/` | `domibus.properties` and per domain the properties and the generated keystore and truststore. |
| `db/` | MySQL init: creates the Domibus schemas and the plugin users on the first start. |
| `setup/setup.sh`, `setup/pmode-template.xml` | Run by `domibus-setup`: generate and upload the PMode of each domain, add onboarded platforms' certificates. |
| `certs/` | `generate-certs.sh` and the generated public certificate of the runner's access point, `tstgate.pem`. |
| `platforms/<partyId>/` | Per platform: runner configuration `tst.yml`; for onboarded platforms also `endpoint.txt`, `certificate.pem`, `connection-info.txt`, `tstgate.pem`. |
| `reports/<partyId>/` | Test reports (`report.html`, `run.json`). |

## How provisioning works

Nothing has to be clicked in the admin console:

1. On the first start, `db/01-domibus.sh` creates the database schemas of both domains, the plugin users and the admin console user `super`.
2. On every start, `domibus-setup` uploads the PMode of each domain (Domibus ext REST API `/ext/pmode`) and
   adds the certificate of each onboarded platform to the `tstgate` truststore (`/ext/truststore/entries`,
   alias = party ID). Onboarded platforms are inserted into the `tstgate` PMode at the `ONBOARDED_*` markers of
   the template. A platform Domibus rejects (e.g. an invalid certificate) is skipped with a warning, so it cannot
   stop the setup; only while it is being onboarded does the rejection fail the command.
3. `sh tst.sh reset` deletes the database (all messages) and does 1 and 2 again; `platforms/` and `reports/`
   are kept.

## Running all tests without an operator

To check the test suite itself, `run-all.py` plays the operator on the pseudo-platform (needs Python 3). With the setup
started:

```sh
python3 run-all.py              # all data sets with test data
python3 run-all.py DS1 DS4      # only these
```

It clears the data sets and messages on the pseudo-platform page first and keeps the fault toggles, so a ticked toggle
makes the run fail. The exit code is the runner's: `0` passed, `1` a test case failed, `2` technical error. The report is
in `reports/pseudoplatform/`. `tst.sh run` only rebuilds the runner, so rebuild the pseudo-platform image after changing
its code.

## Hosting your access point in the test setup

For a platform that talks to its access point through the WS plugin, but has no access point the runner's access point can reach:
add it as another domain of the Domibus in this setup. This tests the platform's messages and behaviour, not its own
access point, so T-ALL-01 and T-ALL-02 say nothing about that one.

The steps change checked-in files and delete all messages. Run them in `deploy/`, with `myplatform` standing for your
party ID, here only lowercase letters, digits and `_`, as it also names the database schema.

1. Create the configuration of the domain:

   ```sh
   mkdir domibus/conf/domains/myplatform
   sed 's/pseudoplatform/myplatform/g' domibus/conf/domains/pseudoplatform/pseudoplatform-domibus.properties \
       > domibus/conf/domains/myplatform/myplatform-domibus.properties
   ```

2. Add `myplatform` to the domain lists `DOMIBUS_DOMAINS` in `db/01-domibus.sh` and `DOMAINS` in `setup/setup.sh`.
3. In `setup/pmode-template.xml`, add the party next to `pseudoplatform`, once in each of the three places:

   ```xml
   <party name="myplatform" endpoint="http://domibus:8080/domibus/services/msh?domain=myplatform">
     <identifier partyId="myplatform" partyIdType="partyTypeUrn"/>
   </party>
   <initiatorParty name="myplatform"/>
   <responderParty name="myplatform"/>
   ```

4. Delete the certificate of the runner's access point, so that step 5 generates new keys for all domains and they trust the new one.
   Access points of onboarded platforms must then import the new `certs/tstgate.pem`.

   ```sh
   rm certs/tstgate.pem
   ```

5. Create the runner configuration and start from an empty database, as the schemas are only created on the first start:

   ```sh
   mkdir platforms/myplatform
   sed 's/pseudoplatform/myplatform/g' platforms/pseudoplatform/tst.yml > platforms/myplatform/tst.yml
   sh tst.sh reset
   ```

6. Connect your platform to the WS plugin `http://localhost:18080/domibus/services/wsplugin` with the user
   `myplatform_ws` and the password `test123`. It sends as party `myplatform` to party `tstgate`; service, action and
   party ID type are in `setup/pmode-template.xml`. If the setup runs on another machine, reach the plugin through a tunnel:
   `ssh -N -L 18080:localhost:18080 <server>`.
7. Fill in the `report:` section of `platforms/myplatform/tst.yml` and run `sh tst.sh run myplatform`.

To remove the domain again, revert these changes and run `sh tst.sh reset`.

## Certificates

The first `sh tst.sh start` runs `certs/generate-certs.sh`, which creates a test CA, per domain (directory under
`domibus/conf/domains/`) a `keystore.p12` and `truststore.p12` (passwords `test123`) and the public
certificate of the runner's access point, `certs/tstgate.pem`. These files belong to this installation and are not versioned, so its private keys are
not shared with anyone else who has the repository.

To renew the keys, delete `certs/tstgate.pem` and run `sh tst.sh start`; this also recreates the containers, as Domibus
works on a copy of the stores. The CA's private key is not kept, so renewing regenerates all keys, and access points of
onboarded platforms must import the new certificate.

For several installations testing against one access point, copy `certs/tstgate.pem` and the `.p12` files under
`domibus/conf/domains/` from one installation to the others before their first start, so that the access point only has
to trust one certificate.

## Limitations

- **Plain HTTP:** the AS4 link has no TLS, and `onboard` accepts `http://` endpoints only.
- **Pseudo-platform state is in memory:** a restart of the service loses its data sets.
