#!/bin/sh
# Generates the test PKI of this installation (run by tst.sh start if certs/tstgate.pem is missing, which is written
# last): a CA and one CA-signed key pair per domain (= party, one directory each under
# domibus/conf/domains/), written as keystore.p12 (own key, alias = party ID) and truststore.p12 (CA + all
# party certificates, alias = party ID, which Domibus uses to look up the receiver's encryption certificate)
# into each domain dir, plus the public certificate of the test runner's access point as certs/tstgate.pem (handed out by
# tst.sh onboard). The CA's private key is discarded: renewing means regenerating everything.
# TEST ONLY: all store passwords are "test123".
set -eu
cd "$(dirname "$0")"

KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
PASS=test123
VALIDITY_DAYS=3650
DOMAINS_DIR=../domibus/conf/domains
PARTIES=$(cd "$DOMAINS_DIR" && ls -d -- */ | tr -d /)
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/tst-certs.XXXXXX")
trap 'rm -rf "$WORK_DIR"' EXIT
CA_STORE="$WORK_DIR/ca.p12"

keytool() { "$KEYTOOL" "$@" -storepass "$PASS" -noprompt; }

keytool -genkeypair -alias ca -dname "CN=eFTI Test CA" -ext bc:c -keyalg RSA -keysize 2048 \
    -validity "$VALIDITY_DAYS" -keystore "$CA_STORE"
keytool -exportcert -rfc -alias ca -keystore "$CA_STORE" -file "$WORK_DIR/ca.pem"

for party in $PARTIES; do
    keystore="$DOMAINS_DIR/$party/keystore.p12"
    rm -f "$keystore"
    keytool -genkeypair -alias "$party" -dname "CN=$party,O=eFTI Test" -keyalg RSA -keysize 2048 \
        -validity "$VALIDITY_DAYS" -keystore "$keystore"
    keytool -certreq -alias "$party" -keystore "$keystore" -file "$WORK_DIR/$party.csr"
    keytool -gencert -alias ca -keystore "$CA_STORE" -validity "$VALIDITY_DAYS" -rfc \
        -ext ku:c=digitalSignature,keyEncipherment -infile "$WORK_DIR/$party.csr" -outfile "$WORK_DIR/$party.pem"
    # The CA must be known to the keystore to import the signed reply as a chain; it is removed again
    # so the keystore only holds the party's own key entry.
    keytool -importcert -alias ca -file "$WORK_DIR/ca.pem" -keystore "$keystore"
    keytool -importcert -alias "$party" -file "$WORK_DIR/$party.pem" -keystore "$keystore"
    keytool -delete -alias ca -keystore "$keystore"
done

for party in $PARTIES; do
    truststore="$DOMAINS_DIR/$party/truststore.p12"
    rm -f "$truststore"
    keytool -importcert -alias ca -file "$WORK_DIR/ca.pem" -keystore "$truststore"
    for trusted in $PARTIES; do
        keytool -importcert -alias "$trusted" -file "$WORK_DIR/$trusted.pem" -keystore "$truststore"
    done
done

cp "$WORK_DIR/tstgate.pem" tstgate.pem
