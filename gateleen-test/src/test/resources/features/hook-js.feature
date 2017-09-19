Feature: Hooking for JavaScript clients

  Background:
    Given Chrome has been started
    And the hook-js UI is displayed

  Scenario: Place a single hook and put a single PUT
    When we click on the button "Place Single Hook"
    Then we see the message "Installing listener 1" at position 1
    When we click on the button "PUT Single"
    Then we see the message "Listener 1 received:<Message 1>" at position 2

  Scenario: Place a collection hook and put a collection of PUTs
    When we click on the button "Place Collection Hook"
    Then we see the message "Installing listener 1" at position 1
    When we click on the button "PUT Collection"
    Then we see the message "Listener 1 received:[resource-1] <Message 1>" at position 2

  Scenario: Put a single PUT and place a single fetching hook
    When we click on the button "PUT Single"
    And we click on the button "Place Single Fetching Hook"
    Then we see the message "Installing listener 1" at position 1
    And we see the message "Listener 1 received:<Message 1>" at position 2

  Scenario: Put a collection of PUTs and place a collection fetching hook
    When we click on the button "PUT Collection"
    And we click on the button "Place Collection Fetching Hook"
    Then we see the message "Installing listener 1" at position 1
    And we see the message "Listener 1 received:[resource-1] <Message 1>" at position 2

  Scenario: Remove last hook
    Given we click on the button "Place Single Hook"
    And we see the message "Installing listener 1" at position 1
    And we click on the button "PUT Single"
    And we see the message "Listener 1 received:<Message 1>" at position 2
    When we click on the button "Remove Last Hook"
    Then we see the message "Removing listener 1" at position 3
    When we click on the button "PUT Single"
    Then we see no message at position 4

  Scenario: Clear log
    Given we click on the button "Place Single Hook"
    And we see the message "Installing listener 1" at position 1
    And we click on the button "PUT Single"
    And we see the message "Listener 1 received:<Message 1>" at position 2
    When we click on the button "Clear log"
    Then we see no message at position 1
    And we see no message at position 2

  Scenario: Clear collection
    Given we click on the button "PUT Collection"
    And we click on the button "Place Collection Fetching Hook"
    And we see the message "Installing listener 1" at position 1
    And we see the message "Listener 1 received:[resource-1] <Message 1>" at position 2
    When we click on the button "Clear collection"
    And we click on the button "Place Collection Fetching Hook"
    Then we see the message "Installing listener 2" at position 3
    And we see no message at position 4