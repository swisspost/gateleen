Feature: hook-js UI Test

  Background:
    Given Chrome has been started
    And the hook-js UI is displayed

  Scenario: Place a sinlge hook and put single
    When we click on the button "Place Single Hook"
    Then we see the message "Installing listener 1" on position 1
    When we click on the button "PUT Single"
    Then we see the message "Listener 1 received:<Message 1>" on position 2
