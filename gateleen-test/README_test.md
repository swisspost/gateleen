# gateleen-test

**Precondition:** To run these tests, a running Redis instance is needed.

These tests are are skipped by default. Running the following maven goal will not run the tests:
> mvn clean install

To run the tests, add the **-DskipTests=false** property:
> mvn clean install -DskipTests=false