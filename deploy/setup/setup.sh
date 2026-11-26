#!/bin/sh
# Provisioning via the Domibus ext REST API, authenticated as the domain's WS plugin user seeded by
# db/01-domibus.sh. Runs as the one-shot service domibus-setup on every start and from tst.sh after
# (off)boarding a platform:
#   1. waits for Domibus,
#   2. "--remove <partyId>" only: removes that platform's certificate from the tstgate truststore,
#   3. adds the certificate of every onboarded platform (/platforms/<partyId>/certificate.pem) to the
#      tstgate truststore under the party ID as alias (replacing an existing entry). A platform Domibus
#      rejects is skipped with a warning, so it cannot stop the whole setup; only with "--onboard <partyId>"
#      a rejection of that platform fails the run,
#   4. uploads the PMode of every domain; only the tstgate PMode lists the onboarded platforms.
# Re-running it is safe; Domibus keeps the previous PModes in its archive.
set -eu

DOMIBUS_URL=http://domibus:8080/domibus
# The domains are the directories under domibus/conf/domains/; also listed in db/01-domibus.sh.
DOMAINS="tstgate pseudoplatform"
GATE_DOMAIN=tstgate
PLUGIN_PASSWORD=test123
MAX_WAIT_SECONDS=900
WORK_DIR=$(mktemp -d)
OPTION=${1:-}
OPTION_PARTY=${2:-}

# $1 = domain, remaining arguments are passed to curl
api() {
    domain=$1
    shift
    status=0
    curl -sS --fail-with-body -u "${domain}_ws:$PLUGIN_PASSWORD" "$@" || status=$?
    echo
    return "$status"
}

xml_escape() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g'
}

# Adds the platform in $1 to the truststore and to the PMode parts; fails without adding anything to the PMode.
add_platform() {
    party=$(basename "$1")
    endpoint=$(cat "$1/endpoint.txt") || return 1
    printf 'Adding certificate of platform %s to the %s truststore\n' "$party" "$GATE_DOMAIN"
    api "$GATE_DOMAIN" -F "file=@$1/certificate.pem" -F "alias=$party" "$DOMIBUS_URL/ext/truststore/entries" || return 1
    party=$(xml_escape "$party")
    printf '      <party name="%s" endpoint="%s">\n        <identifier partyId="%s" partyIdType="partyTypeUrn"/>\n      </party>\n' \
        "$party" "$(xml_escape "$endpoint")" "$party" >> "$WORK_DIR/parties.xml"
    printf '        <initiatorParty name="%s"/>\n' "$party" >> "$WORK_DIR/initiators.xml"
    printf '        <responderParty name="%s"/>\n' "$party" >> "$WORK_DIR/responders.xml"
}

waited=0
# The admin console only answers once the Domibus web application has fully started.
until curl -fs -m 10 -o /dev/null "$DOMIBUS_URL/"; do
    if [ "$waited" -ge "$MAX_WAIT_SECONDS" ]; then
        echo "Domibus not ready after ${MAX_WAIT_SECONDS}s, see: docker compose logs domibus" >&2
        exit 1
    fi
    sleep 5
    waited=$((waited + 5))
done

if [ "$OPTION" = "--remove" ]; then
    printf 'Removing certificate of platform %s from the %s truststore\n' "$OPTION_PARTY" "$GATE_DOMAIN"
    # Not fatal: the entry is already gone if Domibus was recreated in the meantime.
    api "$GATE_DOMAIN" -X DELETE "$DOMIBUS_URL/ext/truststore/entries/$OPTION_PARTY" || true
fi

: > "$WORK_DIR/parties.xml"
: > "$WORK_DIR/initiators.xml"
: > "$WORK_DIR/responders.xml"
for dir in /platforms/*/; do
    [ -f "$dir/certificate.pem" ] || continue
    dir=${dir%/}
    if ! add_platform "$dir"; then
        if [ "$OPTION" = "--onboard" ] && [ "$(basename "$dir")" = "$OPTION_PARTY" ]; then
            exit 1
        fi
        printf 'WARNING: skipped platform %s, Domibus rejected it (details: sh tst.sh logs domibus-setup); offboard it or fix platforms/%s/\n' \
            "$(basename "$dir")" "$(basename "$dir")" >&2
    fi
done

for domain in $DOMAINS; do
    pmode="$WORK_DIR/$domain-pmode.xml"
    sed "s/SELF_PARTY/$domain/" /setup/pmode-template.xml > "$pmode"
    # The pseudo platform only talks to the test runner's access point, so only that one needs to know the onboarded platforms.
    if [ "$domain" = "$GATE_DOMAIN" ]; then
        sed -i -e "/<!-- ONBOARDED_PARTIES -->/r $WORK_DIR/parties.xml" \
            -e "/<!-- ONBOARDED_INITIATORS -->/r $WORK_DIR/initiators.xml" \
            -e "/<!-- ONBOARDED_RESPONDERS -->/r $WORK_DIR/responders.xml" \
            "$pmode"
    fi
    echo "Uploading PMode for domain $domain"
    api "$domain" -F "file=@$pmode;type=text/xml" -F "description=Test setup" "$DOMIBUS_URL/ext/pmode"
done
