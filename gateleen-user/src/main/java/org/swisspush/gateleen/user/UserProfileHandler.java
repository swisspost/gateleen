package org.swisspush.gateleen.user;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.event.TrackableEventPublish;
import org.swisspush.gateleen.core.http.RequestLoggerFactory;
import org.swisspush.gateleen.core.logging.LoggableResource;
import org.swisspush.gateleen.core.logging.RequestLogger;
import org.swisspush.gateleen.core.storage.ResourceStorage;
import org.swisspush.gateleen.core.util.ResponseStatusCodeLogUtil;
import org.swisspush.gateleen.core.util.RoleExtractor;
import org.swisspush.gateleen.core.util.StatusCode;
import org.swisspush.gateleen.logging.LoggingResourceManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class UserProfileHandler implements LoggableResource {

    private final TrackableEventPublish trackableEventPublish;
    private Vertx vertx;
    private ResourceStorage storage;

    private String roleProfileKey = "profile";
    private UserProfileConfiguration userProfileConfiguration;
    private UserProfileManipulater userProfileManipulater;

    private Logger log = LoggerFactory.getLogger(UserProfileHandler.class);

    private Map<String, JsonObject> roleProfiles = new HashMap<>();
    private RoleExtractor roleExtractor;

    private boolean logUserProfileChanges = false;

    /**
     * Constructor for the UserProfileHandler.
     *
     * @param vertx vertx
     * @param storage storage
     * @param userProfileConfiguration userProfileConfiguration
     */
    public UserProfileHandler(Vertx vertx, ResourceStorage storage, UserProfileConfiguration userProfileConfiguration) {
        this.vertx = vertx;
        this.storage = storage;
        this.userProfileConfiguration = userProfileConfiguration;
        this.userProfileManipulater = new UserProfileManipulater(log);
        this.roleExtractor = new RoleExtractor(userProfileConfiguration.getRolePattern());
        this.trackableEventPublish = new TrackableEventPublish(vertx);
        updateRoleProfiles();

        EventBus eb = vertx.eventBus();

        // Receive update notifications
        trackableEventPublish.consumer(RoleProfileHandler.UPDATE_ADDRESS, event -> updateRoleProfiles());
    }

    @Override
    public void enableResourceLogging(boolean resourceLoggingEnabled) {
        this.logUserProfileChanges = resourceLoggingEnabled;
    }

    public boolean isUserProfileRequest(HttpServerRequest request) {
        return userProfileConfiguration.doesUrlMatchTheProfileUriPattern(request.path());
    }

    public void handle(final HttpServerRequest request) {
        RequestLoggerFactory.getLogger(UserProfileHandler.class, request).info("handling " + request.method() + " " + request.path());
        switch (request.method().name()) {
        case "GET":
            storage.get(request.path(), buffer -> {
                request.response().headers().set("Content-Type", "application/json");
                String userId = userProfileConfiguration.extractUserIdFromProfileUri(request.path());
                if (buffer != null) {
                    request.response().setStatusCode(StatusCode.OK.getStatusCode());
                    JsonObject profile = new JsonObject(buffer.toString());
                    final int enrichUpdateCount = userProfileManipulater.enrichProfile(request.headers(),
                            profile, userId, userProfileConfiguration.getProfileProperties().values());
                    final JsonObject mergedProfile = mergeUserProfileWithRoleProfile(request, profile);
                    logPayload(request, StatusCode.OK.getStatusCode(), Buffer.buffer(mergedProfile.encode()),
                            request.response().headers());
                    ResponseStatusCodeLogUtil.info(request, StatusCode.OK, UserProfileHandler.class);
                    if (enrichUpdateCount == 0) {
                        // Normal case: Nothing changed in profile
                        request.response().end(mergedProfile.encode());
                    } else {
                        // Special case: Something updated in profile
                        log.debug("Updated the profile in a GET request (special case). Request path is {}.", request.path());
                        storage.put(request.path(), Buffer.buffer(profile.encode()), status -> request.response().end(mergedProfile.encode()));
                    }
                } else {
                    // Not Found, returns the initial profile.
                    request.response().setStatusCode(StatusCode.OK.getStatusCode());

                    JsonObject profile = userProfileManipulater.createInitialProfile(request.headers(), userId, userProfileConfiguration.getProfileProperties().values());
                    final JsonObject mergedProfile = mergeUserProfileWithRoleProfile(request, profile);

                    // NEMO-3200, store the profile, otherwise the next request will fail,
                    // cause the server cannot enrich the request create the profile data
                    storage.put(request.path(), Buffer.buffer(profile.encode()), status -> {
                        logPayload(request, status, Buffer.buffer(mergedProfile.encode()), request.response().headers());
                        ResponseStatusCodeLogUtil.info(request, StatusCode.OK, UserProfileHandler.class);
                        request.response().end(mergedProfile.encode());
                    });
                }
            });
            break;
        case "PUT":
            request.pause();
            storage.get(request.path(), existingBuffer -> {
                if (existingBuffer != null) {
                    request.resume();
                } else {
                    // Not Found, create a new profile.
                    log.debug("Tried to put (merge) a profile, path is '{}', but profile was not found. Create a new profile.",
                            ((request.path() == null || request == null) ? "<null>" : request.path()));
                    JsonObject profile = userProfileManipulater.createProfileWithLanguage(request.headers());
                    cleanupUserProfile(profile, updatedProfile -> storage.put(request.path() + "?merge=true", Buffer.buffer(updatedProfile.encode()), status -> request.resume()));
                }
            });

            /**
             * After request.resume() the bodyHandler will be called
             */
            request.bodyHandler(newBuffer -> {
                JsonObject profile = null;
                try {
                    profile = new JsonObject(newBuffer.toString());
                } catch (DecodeException ex) {
                    ResponseStatusCodeLogUtil.info(request, StatusCode.BAD_REQUEST, UserProfileHandler.class);
                    request.response().setStatusCode(StatusCode.BAD_REQUEST.getStatusCode());
                    request.response().end(StatusCode.BAD_REQUEST.getStatusMessage());
                    return;
                }
                cleanupUserProfile(profile, updatedProfile -> storage.put(request.uri() + "?merge=true", Buffer.buffer(updatedProfile.encode()), status -> {
                    logPayload(request, status, Buffer.buffer(updatedProfile.encode()), MultiMap.caseInsensitiveMultiMap());
                    ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), UserProfileHandler.class);
                    request.response().setStatusCode(status);
                    request.response().end();
                }));
            });
            break;
        case "DELETE":
            storage.delete(request.path(), status -> {
                ResponseStatusCodeLogUtil.info(request, StatusCode.fromCode(status), UserProfileHandler.class);
                request.response().setStatusCode(status);
                request.response().end();
            });
            break;
        default:
            ResponseStatusCodeLogUtil.info(request, StatusCode.METHOD_NOT_ALLOWED, UserProfileHandler.class);
            request.response().setStatusCode(StatusCode.METHOD_NOT_ALLOWED.getStatusCode());
            request.response().end(StatusCode.METHOD_NOT_ALLOWED.getStatusMessage());
        }
    }

    protected void cleanupUserProfile(final JsonObject profile, final Handler<JsonObject> profileCallback) {
        log.debug("About to remove 'not allowed' properties from user profile");
        JsonObject profileCopy = profile.copy();
        Set<String> profileFieldNames = profileCopy.fieldNames();
        profileFieldNames.stream().filter(fieldName -> !userProfileConfiguration.isAllowedProfileProperty(fieldName)).forEach(fieldName -> {
            if(userProfileConfiguration.isRemoveNotAllowedProfileProperties()) {
                log.debug("Property '{}' is not allowed, removing it from user profile", fieldName);
                profile.put(fieldName, null);
            } else {
                log.debug("Property '{}' is not allowed, removing it from user profile (only when not already in storage)", fieldName);
                profile.remove(fieldName);
            }
        });
        profileCallback.handle(profile);
    }

    private JsonObject mergeUserProfileWithRoleProfile(HttpServerRequest request, JsonObject userProfile) {
        Set<String> roles = roleExtractor.extractRoles(request);

        JsonObject roleProfileAccumulated = new JsonObject();
        if (roles != null) {
            for (String role : roles) {
                JsonObject roleProfile = roleProfiles.get(role);
                if (roleProfile != null) {
                    roleProfileAccumulated.mergeIn(roleProfile);
                }
            }
        }
        return roleProfileAccumulated.mergeIn(userProfile);
    }

    private void updateRoleProfiles() {
        storage.get(userProfileConfiguration.getRoleProfilesRoot(), buffer -> {
            if (buffer != null) {
                roleProfiles = new HashMap<>();
                for (Object profileObject : new JsonObject(buffer.toString()).getJsonArray("v1")) {
                    String role = (String) profileObject;
                    role = role.replaceAll("/$", "");
                    role = role.replaceAll("^/", "");
                    updateRoleProfile(role);
                }
            } else {
                log.debug("No Role Profiles in storage, remove all roles");
                roleProfiles.clear();
            }
        });
    }

    private void updateRoleProfile(final String role) {
        storage.get(userProfileConfiguration.getRoleProfilesRoot() + role + "/" + roleProfileKey, buffer -> {
            if (buffer != null) {
                try {
                    log.debug("Applying role profile for {}", role);
                    mergeRole(role, buffer);
                } catch (IllegalArgumentException e) {
                    log.error("Could not reconfigure routing", e);
                }
            } else {
                log.error("No profile for role {} found in storage", role);
            }
        });
    }

    private void mergeRole(String role, Buffer buffer) {
        JsonObject roleProfile = new JsonObject(buffer.toString());
        roleProfiles.put(role, roleProfile);
    }

    private void logPayload(final HttpServerRequest request, final Integer status, Buffer data, final MultiMap responseHeaders) {
        if(logUserProfileChanges){
            RequestLogger.logRequest(vertx.eventBus(), request, status, data, responseHeaders);
        }
    }

}
