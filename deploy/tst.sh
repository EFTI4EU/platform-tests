#!/bin/sh
# Entry point for the local eFTI platform test setup; only Docker is required. Run "sh tst.sh help".
set -eu
# Character ranges like [a-z] in patterns depend on the locale (e.g. with bash 3.2 on macOS).
export LC_ALL=C
# BuildKit attaches build provenance to images by default, which gives an unchanged image a new ID on every
# build, so "up" would needlessly recreate Domibus (about 20 s restart).
export BUILDX_NO_DEFAULT_ATTESTATIONS=1

DEPLOY_DIR=$(cd "$(dirname "$0")" && pwd)
PLATFORMS_DIR="$DEPLOY_DIR/platforms"
DOMAINS_DIR="$DEPLOY_DIR/domibus/conf/domains"
PMODE_TEMPLATE="$DEPLOY_DIR/setup/pmode-template.xml"
BUILT_IN_PLATFORM=pseudoplatform
GATE_PARTY=tstgate

usage() {
    cat <<EOF
Local eFTI platform test setup: a Domibus access point for the test runner (domain tstgate), a pseudo
platform for trying things out, and the test runner acting as the eFTI gate. Only Docker is required.

Usage: sh tst.sh <command> [arguments]

  start      Build and start everything and wait until Domibus is configured (first start: several minutes).
             The ports are only reachable from this machine; TST_BIND=0.0.0.0 sh tst.sh start opens the test
             gate's port to other machines, including its admin console with the test logins, so only use it on
             a trusted network. Other host ports: TST_GATE_PORT (default 18080) and TST_PLATFORM_PORT
             (default 18090), set for every command.
  stop       Stop everything; messages, onboarded platforms and reports are kept
  reset      Delete all messages and Domibus state, then start again; onboarded platforms and reports are kept
  status     Show the containers and the onboarded platforms
  logs [service]
             Follow the logs (services: domibus, domibus-setup, mysql, pseudo-platform)

  run [partyId] [dataset ...]
             Run the tests against the pseudo platform (default) or an onboarded platform.
             Interactive: the runner asks for the data set ID and tells you what to do on the platform.
             Configuration: platforms/<partyId>/tst.yml, report: reports/<partyId>/report.html
             Data sets (e.g. DS1 DS2-DG, see testdata/README.md) override the ones of tst.yml for this run;
             without a party ID they run against the pseudo platform.

  onboard <partyId> <endpoint> <certificate.pem> [platformId]
             Connect your platform's access point (AP) to the AP of the test runner:
               partyId          AS4 party ID of your AP (lowercase letters, digits, '.', '_', '-')
               endpoint         AS4 endpoint of your AP (http://, TLS is not supported yet) as reachable
                                from inside the Domibus container. For an AP running on this machine use
                                host.docker.internal instead of localhost,
                                e.g. http://host.docker.internal:8080/domibus/services/msh
               certificate.pem  public certificate your AP signs and encrypts with (PEM format)
               platformId       platform identifier used in UILs (default: partyId)
             Writes platforms/<partyId>/ with the connection details for your AP (connection-info.txt),
             the certificate of the test runner's AP (tstgate.pem) and the runner configuration (tst.yml).
             Onboarded platforms are re-applied on every start and reset.
  offboard <partyId>
             Remove an onboarded platform (deletes platforms/<partyId>/)

Example:
  sh tst.sh start
  sh tst.sh onboard myplatform http://host.docker.internal:8080/domibus/services/msh ./myplatform.pem
  sh tst.sh run myplatform
EOF
}

die() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

compose() {
    docker compose -f "$DEPLOY_DIR/docker-compose.yml" "$@"
}

check_docker() {
    command -v docker > /dev/null 2>&1 || die "docker not found. Install Docker Desktop or Docker Engine with the compose plugin."
    docker info > /dev/null 2>&1 || die "Docker is not running (or this user may not use it). Start Docker and try again."
    docker compose version > /dev/null 2>&1 || die "'docker compose' is not available. Install the Docker Compose plugin."
}

require_running() {
    compose ps --status running --services | grep -qx domibus || die "the test setup is not running. Start it with: sh tst.sh start"
}

# A failed setup leaves Domibus running without PModes, and "run" does not start the setup again.
require_setup_done() {
    [ "$(compose ps -a --status exited --format '{{.ExitCode}}' domibus-setup)" = 0 ] ||
        die "the Domibus setup has not finished successfully (sh tst.sh logs domibus-setup). Start again with: sh tst.sh start"
}

# Party IDs are folder names, URL parts and truststore aliases (which are case-insensitive), so only a safe
# subset of characters is allowed.
require_party_id() {
    case $1 in
        '' | [.-]* | *[!a-z0-9._-]*) die "invalid party ID '$1': use lowercase letters, digits, '.', '_' and '-' only" ;;
    esac
}

require_platform_id() {
    case $1 in
        '' | [.-]* | *[!A-Za-z0-9._-]*) die "invalid platform ID '$1': use letters, digits, '.', '_' and '-' only" ;;
    esac
}

# Host port of a running service; the port settings are in docker-compose.yml only.
published_port() {
    address=$(compose port "$1" "$2" | head -n 1)
    [ -n "$address" ] || die "service $1 is not running. Start the setup with: sh tst.sh start"
    printf '%s\n' "${address##*:}"
}

gate_endpoint() {
    printf 'http://localhost:%s/domibus/services/msh?domain=%s\n' "$(published_port domibus 8080)" "$GATE_PARTY"
}

# Attribute $2 of the first element $1 in the PMode template, e.g.: pmode_value service type
pmode_value() {
    sed -n "s/.*<$1 [^>]*$2=\"\([^\"]*\)\".*/\1/p" "$PMODE_TEMPLATE" | head -n 1
}

# Re-applies the onboarded platforms (truststore entries and PModes) in the running Domibus.
apply_platforms() {
    compose run --rm domibus-setup "$@"
}

print_urls() {
    cat <<EOF

Ready:
  Pseudo platform UI:      http://localhost:$(published_port pseudo-platform 18090)
  Domibus admin console:   http://localhost:$(published_port domibus 8080)/domibus/  (user super, password 123456 - test only)
  Runner AP AS4 endpoint:  $(gate_endpoint)
Run the tests: sh tst.sh run [partyId]
EOF
}

# Domibus works on a copy of the stores made when its container is created, so new keys need a new container.
# The Domibus image is used because it contains keytool. generate-certs.sh writes tstgate.pem last, so an interrupted
# run is repeated on the next start.
ensure_certs() {
    [ ! -f "$DEPLOY_DIR/certs/tstgate.pem" ] || return 0
    echo "Generating the keys of this installation ..."
    compose down
    compose run --rm --no-deps --user "$(id -u):$(id -g)" --volume "$DEPLOY_DIR:/deploy" --entrypoint sh domibus \
        /deploy/certs/generate-certs.sh || die "generating the keys failed, see above"
    for copy in "$PLATFORMS_DIR"/*/tstgate.pem; do
        [ ! -f "$copy" ] || {
            cp "$DEPLOY_DIR/certs/tstgate.pem" "$copy"
            printf 'New certificate of the test runner AP: import %s into your access point again\n' "$copy"
        }
    done
}

start() {
    echo "Building and starting (the first start downloads and builds images and takes several minutes) ..."
    # Built before "up" instead of "up --build", so that the runner image is ready for the first run too.
    compose --profile runner build || die "building the images failed, see above"
    ensure_certs
    started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    # Returns only after domibus-setup has finished, because pseudo-platform waits for it. The second "up" returns
    # once the pseudo platform answers; Compose versions differ in whether --wait on all services accepts that
    # domibus-setup has exited.
    if ! compose up -d || ! compose up -d --no-deps --wait pseudo-platform; then
        compose logs --tail 30 domibus-setup >&2 || true
        die "start failed, see above. Domibus log: sh tst.sh logs domibus"
    fi
    # E.g. onboarded platforms that Domibus rejected and that were skipped
    compose logs --no-log-prefix --since "$started" domibus-setup | grep '^WARNING' >&2 || true
    print_urls
    [ "${TST_BIND:-127.0.0.1}" = 127.0.0.1 ] || cat >&2 <<EOF

WARNING: TST_BIND=$TST_BIND opens the port of the test runner's access point. Everyone who can reach this machine can use its admin
console and WS plugin with the public test logins. Only use this on a trusted network.
EOF
}

status() {
    compose ps -a
    echo
    echo "Onboarded platforms:"
    for dir in "$PLATFORMS_DIR"/*/; do
        [ -f "$dir/certificate.pem" ] && printf '  %s -> %s\n' "$(basename "$dir")" "$(cat "$dir/endpoint.txt")"
    done
    return 0
}

run_tests() {
    # Party IDs are lowercase and data set keys start with an uppercase letter, so the party ID can be left out.
    case ${1:-} in
        [[:upper:]]*) party=$BUILT_IN_PLATFORM ;;
        *) party=${1:-$BUILT_IN_PLATFORM}; [ $# -eq 0 ] || shift ;;
    esac
    datasets=$(IFS=,; printf '%s' "$*")
    require_party_id "$party"
    [ -f "$PLATFORMS_DIR/$party/tst.yml" ] || die "no runner configuration platforms/$party/tst.yml; onboard the platform first (sh tst.sh help)"
    require_running
    require_setup_done
    mkdir -p "$DEPLOY_DIR/reports"
    # "run --build" would also rebuild the dependencies and may recreate Domibus, so only the runner is built.
    compose build runner
    result=0
    # Runs as the calling user so that the report files on the host belong to them (matters on Linux).
    # Without --no-deps, "run" would start the finished domibus-setup again before every run.
    compose run --rm --no-deps --user "$(id -u):$(id -g)" runner --config "/platforms/$party/tst.yml" \
        ${datasets:+--datasets "$datasets"} || result=$?
    echo "Report folder on this machine: $DEPLOY_DIR/reports/$party"
    exit "$result"
}

# The configuration of an onboarded platform is derived from the pseudo platform's.
runner_config() {
    party=$1
    platform_id=$2
    printf '%s\n' "# Runner configuration for platform $party, generated by tst.sh onboard from" \
        "# platforms/$BUILT_IN_PLATFORM/tst.yml. Fill in the report: section before an official run." \
        "# Paths are inside the runner container."
    sed -e '1,/^$/d' \
        -e "s|^outputDir: .*|outputDir: /reports/$party|" \
        -e "s|^\(  partyId:\) $BUILT_IN_PLATFORM|\1 $party|" \
        -e "s|^\(  id:\) $BUILT_IN_PLATFORM|\1 $platform_id|" \
        -e "s|^\(  platformName:\) .*|\1 $party|" \
        -e "s|^\(  platformVersion:\) .*|\1 TODO|" \
        -e "s|^\(  company:\) .*|\1 TODO|" \
        "$PLATFORMS_DIR/$BUILT_IN_PLATFORM/tst.yml"
}

# Service, action and party ID type are read from the PMode template; the message properties are the values
# the domibus-client sends (SoapMessages).
connection_info() {
    party=$1
    platform_id=$2
    endpoint=$3
    cat <<EOF
Connecting the access point (AP) of platform $party to the AP of the local test runner

TEST RUNNER AP
  AS4 endpoint      $(gate_endpoint)
                    (AP in a Docker container on this machine: host.docker.internal instead of localhost;
                    AP on another machine: this machine's host name or IP address, after starting the
                    setup with TST_BIND=0.0.0.0 sh tst.sh start)
  Transport         plain HTTP, no TLS
  Party ID          $GATE_PARTY
  Party ID type     $(pmode_value partyIdType value)
  Certificate       tstgate.pem (signing and encryption, alias $GATE_PARTY)

YOUR AP AS CONFIGURED AT THE TEST RUNNER AP
  Party ID          $party (same party ID type)
  AS4 endpoint      $endpoint
  Certificate       certificate.pem (must be the one your AP signs with and decrypts with)
  Platform ID       $platform_id (in UILs)

USER MESSAGES (both directions)
  MEP / binding     one-way / push
  Roles             initiator: http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/initiator
                    responder: http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/responder
  Service           $(pmode_value service value) (type $(pmode_value service type))
  Action            $(pmode_value action value)
  Agreement         none
  Properties        originalSender and finalRecipient are required; the test runner sends
                    originalSender = urn:oasis:names:tc:ebcore:partyid-type:unregistered:C1
                    finalRecipient = urn:oasis:names:tc:ebcore:partyid-type:unregistered:C4
  Payload           one XML part, href cid:message, MimeType text/xml (AS4 compression is used)
  Correlation       every response sets RefToMessageId to the AS4 message ID of the request it
                    answers (eFTI technical guidance, Annex 1 ch. 3)
  Security          eDeliveryAS4Policy, Domibus 5.2.1 defaults: eb:Messaging, SOAP body and
                    attachments signed with RSA-SHA256 (digest SHA-256); attachments encrypted with
                    AES-128-GCM, key transport RSA-OAEP (MGF1-SHA256); certificates referenced by
                    issuer and serial number
  Reliability       signed receipts (non-repudiation), duplicate detection; the test runner AP retries a
                    failed delivery 4 times within 1 minute
EOF
}

onboard() {
    [ $# -ge 3 ] && [ $# -le 4 ] || die "usage: sh tst.sh onboard <partyId> <endpoint> <certificate.pem> [platformId]"
    party=$1
    endpoint=$2
    cert=$3
    platform_id=${4:-$1}
    require_party_id "$party"
    require_platform_id "$platform_id"
    [ ! -d "$DOMAINS_DIR/$party" ] || die "$party is built in, choose another party ID"
    [ "$party" != ca ] || die "ca is the alias of the test CA in the truststore, choose another party ID"
    [ ! -e "$PLATFORMS_DIR/$party" ] || die "platform $party is already onboarded. To change it, first run: sh tst.sh offboard $party"
    case $endpoint in
        http://*) ;;
        https://*) die "https endpoints are not supported yet (the test runner's AP has no TLS truststore); use an http:// endpoint" ;;
        *) die "the endpoint must start with http://" ;;
    esac
    case $endpoint in
        *[[:space:]]*) die "the endpoint must not contain spaces" ;;
        *://localhost[:/]* | *://127.0.0.1[:/]*)
            die "localhost would be the Domibus container itself; for an AP on this machine use host.docker.internal" ;;
    esac
    [ -f "$cert" ] || die "certificate file not found: $cert"
    grep -q -- '-----BEGIN CERTIFICATE-----' "$cert" ||
        die "$cert is not a PEM certificate (no '-----BEGIN CERTIFICATE-----' line). Convert a DER file with: openssl x509 -inform der -in cert.der -out cert.pem"
    ! grep -q -- 'PRIVATE KEY-----' "$cert" || die "$cert contains a private key; only the public certificate is needed"
    require_running

    # Every start applies all folders in platforms/, so the folder is only moved there once it is complete
    # (folders starting with a dot are ignored).
    staging="$PLATFORMS_DIR/.$party.incomplete"
    rm -rf "$staging"
    trap 'rm -rf "$staging"' EXIT
    mkdir "$staging"
    cp "$cert" "$staging/certificate.pem"
    printf '%s\n' "$endpoint" > "$staging/endpoint.txt"
    cp "$DEPLOY_DIR/certs/tstgate.pem" "$staging/tstgate.pem"
    runner_config "$party" "$platform_id" > "$staging/tst.yml"
    connection_info "$party" "$platform_id" "$endpoint" > "$staging/connection-info.txt"
    mv "$staging" "$PLATFORMS_DIR/$party"
    if ! apply_platforms --onboard "$party"; then
        rm -r "${PLATFORMS_DIR:?}/$party"
        apply_platforms --remove "$party" > /dev/null 2>&1 || true
        die "Domibus rejected the platform (see the message above), nothing was onboarded"
    fi

    cat "$PLATFORMS_DIR/$party/connection-info.txt"
    cat <<EOF

Onboarded $party. Files in $PLATFORMS_DIR/$party:
  connection-info.txt  the details above, for configuring your AP
  tstgate.pem          certificate of the test runner's AP to import into your AP
  tst.yml              runner configuration: fill in the report: section
Then run the tests: sh tst.sh run $party
EOF
}

offboard() {
    [ $# -eq 1 ] || die "usage: sh tst.sh offboard <partyId>"
    require_party_id "$1"
    [ -f "$PLATFORMS_DIR/$1/certificate.pem" ] || die "platform $1 is not onboarded (no platforms/$1/certificate.pem)"
    require_running
    rm -r "${PLATFORMS_DIR:?}/$1"
    apply_platforms --remove "$1"
    printf 'Offboarded %s\n' "$1"
}

command=${1:-help}
[ $# -gt 0 ] && shift
case $command in
    help | -h | --help) usage; exit 0 ;;
esac
check_docker
case $command in
    start) start ;;
    stop) compose down ;;
    reset) compose down -v && start ;;
    status) status ;;
    logs) compose logs -f "$@" ;;
    run) run_tests "$@" ;;
    onboard) onboard "$@" ;;
    offboard) offboard "$@" ;;
    *) usage >&2; die "unknown command: $command" ;;
esac
