#!/bin/sh
set -eu

# Resolvers from Docker's generated resolv.conf are the only private destinations this worker needs.
iptables --wait -F OUTPUT
iptables --wait -P OUTPUT DROP
for resolver in $(awk '/^nameserver[[:space:]]+/ { print $2 }' /etc/resolv.conf)
do
    case "$resolver" in
        *:*) ;;
        *)
            iptables --wait -A OUTPUT -d "$resolver/32" -p udp --dport 53 -j ACCEPT
            iptables --wait -A OUTPUT -d "$resolver/32" -p tcp --dport 53 -j ACCEPT
            ;;
    esac
done
iptables --wait -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

for network in \
    0.0.0.0/8 \
    10.0.0.0/8 \
    100.64.0.0/10 \
    127.0.0.0/8 \
    169.254.0.0/16 \
    172.16.0.0/12 \
    192.0.0.0/24 \
    192.0.2.0/24 \
    192.168.0.0/16 \
    198.18.0.0/15 \
    198.51.100.0/24 \
    203.0.113.0/24 \
    224.0.0.0/4
do
    iptables --wait -A OUTPUT -d "$network" -j REJECT
done

iptables --wait -A OUTPUT -p tcp -m multiport --dports 80,443 -j ACCEPT

HEAP_MB="${GOALMAKER_WORKER_HEAP_MB:-192}"
ACTIVE_PROCESSORS="${GOALMAKER_WORKER_ACTIVE_PROCESSORS:-1}"

exec setpriv \
    --reuid=65532 \
    --regid=65532 \
    --clear-groups \
    --no-new-privs \
    --bounding-set=-all \
    --inh-caps=-all \
    --ambient-caps=-all \
    /opt/java/openjdk/bin/java \
    "-Xmx${HEAP_MB}m" \
    -Xss512k \
    -XX:MaxMetaspaceSize=128m \
    -XX:ReservedCodeCacheSize=64m \
    "-XX:ActiveProcessorCount=${ACTIVE_PROCESSORS}" \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.awt.headless=true \
    -Duser.home=/tmp \
    -Dloader.main=com.example.goalmaker.FetchWorkerMain \
    -cp /app/goalmaker.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher
