package org.swisspush.gateleen.qos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Represents a rule for the QoS Handler. <br>
 * 
 * @author https://github.com/ljucam [Mario Ljuca]
 */
public class QoSRule {
    private Double reject;
    private Double warn;
    private Pattern urlPattern;
    private Set<String> actions;

    /**
     * Creates a new QoS rule object with the
     * given pattern.
     * 
     * @param urlPattern the url pattern for this rule
     */
    public QoSRule(Pattern urlPattern) {
        this.urlPattern = urlPattern;
        actions = new HashSet<>();
    }

    /**
     * Returns the ratio which indicates
     * if the request should be rejected
     * or not.
     * 
     * @return the ratio
     */
    public Double getReject() {
        return reject;
    }

    /**
     * Sets the ratio which indicates
     * if the request should be rejected
     * or not.
     * 
     * @param reject the ratio
     */
    public void setReject(Double reject) {
        this.reject = reject;
    }

    /**
     * Returns the ratio which indicates
     * if the request should be logged
     * with a warning or not.
     * 
     * @return the ratio
     */
    public Double getWarn() {
        return warn;
    }

    /**
     * Returns the ratio which indicates
     * if the request should be logged
     * with a warning or not.
     * 
     * @param warn the ratio
     */
    public void setWarn(Double warn) {
        this.warn = warn;
    }

    /**
     * Returns a precompiled pattern
     * for this rule.
     * 
     * @return precompiled pattern
     */
    public Pattern getUrlPattern() {
        return urlPattern;
    }

    /**
     * Indicates if an action has to be performed or not.
     * 
     * @return true if an action has to be performed, otherwise false
     */
    public boolean performAction() {
        return !actions.isEmpty();
    }

    /**
     * Returns a list of actions which hase to be performed.
     * 
     * @return a list of actions which have to be performed
     */
    public List<String> getActions() {
        return new ArrayList<>(actions);
    }

    /**
     * Adds an action.
     * 
     * @param action the action
     */
    public void addAction(String action) {
        actions.add(action);
    }

    /**
     * Removes the action.
     * 
     * @param action the action
     */
    public void removeAction(String action) {
        actions.remove(action);
    }

    /**
     * Removes all actions for this rule.
     */
    public void clearAction() {
        actions.clear();
    }
}
