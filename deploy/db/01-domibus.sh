#!/bin/bash
# Run by the MySQL image entrypoint on the first start. The entrypoint sources this file, or executes it if
# it is executable, which every file is on a Windows drive. So it works both ways: it uses none of the
# entrypoint's functions and aborts the initialization on errors itself.
# Creates the Domibus multi-tenancy schemas from the official, unmodified DDL in ddl/ and seeds one
# WS plugin user per domain (TEST ONLY: password "test123", bcrypt hash below) and the admin console
# user super (TEST ONLY: password "123456").
set -e

DOMIBUS_DDL_DIR=/docker-entrypoint-initdb.d/ddl
# The domains are the directories under domibus/conf/domains/; also listed in setup/setup.sh.
DOMIBUS_DOMAINS="tstgate pseudoplatform"
PLUGIN_PASSWORD_BCRYPT='$2a$10$rmzHNIL43mCj9j29gpEfNeBYiEdK.4eODSpxqyNmT/mmcW5QLbyyu'
SUPER_PASSWORD_BCRYPT='$2a$10$33QnAyNnZ6pjwvGBfcAWae./QKRW2mxe608dc/LQlvdAdNuOEnvsO'

domibus_sql() {
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --comments "$@"
}

create_domibus_schema() {
    domibus_sql -e "CREATE SCHEMA $1 CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
        GRANT ALL PRIVILEGES ON $1.* TO 'edelivery'@'%';"
    domibus_sql --database="$1" < "$DOMIBUS_DDL_DIR/$2.ddl"
    domibus_sql --database="$1" < "$DOMIBUS_DDL_DIR/$2-data.ddl"
}

domibus_sql -e "CREATE USER 'edelivery'@'%' IDENTIFIED BY 'edelivery';
    GRANT XA_RECOVER_ADMIN ON *.* TO 'edelivery'@'%';"

create_domibus_schema general_schema mysql-5.2.1-multi-tenancy

# Domibus would create super itself, with the preferred domain "default", which does not exist here, so
# the login would fail. The role ID is the one of ROLE_AP_ADMIN in the official data DDL.
domibus_sql --database=general_schema -e "INSERT INTO TB_USER
        (USER_NAME, USER_PASSWORD, USER_ENABLED, DEFAULT_PASSWORD, PASSWORD_CHANGE_DATE)
        VALUES ('super', '$SUPER_PASSWORD_BCRYPT', 1, 0, UTC_TIMESTAMP);
    INSERT INTO TB_USER_ROLES (USER_ID, ROLE_ID) VALUES (LAST_INSERT_ID(), 197001010000000003);
    INSERT INTO TB_USER_DOMAIN (USER_NAME, PREFERRED_DOMAIN) VALUES ('super', 'tstgate');"

for domain in $DOMIBUS_DOMAINS; do
    create_domibus_schema "$domain" mysql-5.2.1
    # Multi-tenancy resolves a plugin user's domain via TB_USER_DOMAIN in the general schema.
    domibus_sql -e "INSERT INTO $domain.TB_AUTHENTICATION_ENTRY
            (USERNAME, PASSWD, AUTH_ROLES, PASSWORD_CHANGE_DATE, DEFAULT_PASSWORD)
            VALUES ('${domain}_ws', '$PLUGIN_PASSWORD_BCRYPT', 'ROLE_ADMIN', UTC_TIMESTAMP, 0);
        INSERT INTO general_schema.TB_USER_DOMAIN (USER_NAME, DOMAIN) VALUES ('${domain}_ws', '$domain');"
done
