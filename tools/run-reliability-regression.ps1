param(
    [switch]$Integration,
    [switch]$Rabbit
)

$ErrorActionPreference = 'Stop'

mvn -pl trade-service,notification-service -am test

if ($Integration) {
    mvn -pl trade-service -Dorder.integration=true test
}

if ($Rabbit) {
    mvn -pl trade-service -Dorder.integration=true -Dorder.rabbit=true test
}
