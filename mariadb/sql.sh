#!/usr/bin/env bash
# Run SQL against the local MariaDB container as the CRM user.
#   ./sql.sh -f sql/01_schema.sql                run a script file (may use DELIMITER)
#   ./sql.sh "select count(*) from customer"     run one statement (";" appended)
#   ./sql.sh -b "BEGIN NOT ATOMIC ... END"       run one compound block (its inner ";" are not statement ends)
#   MARIADB_USER=root MARIADB_PASSWORD=root ./sql.sh ...    other login
set -euo pipefail
U="${MARIADB_USER:-crm}"; P="${MARIADB_PASSWORD:-crm}"; DB="${MARIADB_DB:-crm}"
if [ "${1:-}" = "-f" ]; then
  docker exec -i mariadb mariadb -u"$U" -p"$P" "$DB" --table < "$2"
elif [ "${1:-}" = "-b" ]; then
  printf 'DELIMITER //\n%s//\nDELIMITER ;\n' "$2" | docker exec -i mariadb mariadb -u"$U" -p"$P" "$DB" --table
else
  printf '%s;\n' "$1" | docker exec -i mariadb mariadb -u"$U" -p"$P" "$DB" --table
fi
