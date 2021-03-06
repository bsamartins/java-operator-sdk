package io.javaoperatorsdk.operator.processing;


import io.fabric8.kubernetes.client.CustomResource;
import io.javaoperatorsdk.operator.processing.event.DefaultEventSourceManager;
import io.javaoperatorsdk.operator.processing.event.Event;
import io.javaoperatorsdk.operator.processing.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import static io.javaoperatorsdk.operator.EventListUtils.containsCustomResourceDeletedEvent;
import static io.javaoperatorsdk.operator.processing.KubernetesResourceUtils.getUID;
import static io.javaoperatorsdk.operator.processing.KubernetesResourceUtils.getVersion;

/**
 * Event handler that makes sure that events are processed in a "single threaded" way per resource UID, while buffering
 * events which are received during an execution.
 */

public class DefaultEventHandler implements EventHandler {

    private final static Logger log = LoggerFactory.getLogger(DefaultEventHandler.class);

    private final CustomResourceCache customResourceCache;
    private final EventBuffer eventBuffer;
    private final Set<String> underProcessing = new HashSet<>();
    private final ScheduledThreadPoolExecutor executor;
    private final EventDispatcher eventDispatcher;
    private DefaultEventSourceManager defaultEventSourceManager;

    private final ReentrantLock lock = new ReentrantLock();

    public DefaultEventHandler(CustomResourceCache customResourceCache, EventDispatcher eventDispatcher, String relatedControllerName) {
        this.customResourceCache = customResourceCache;
        this.eventDispatcher = eventDispatcher;
        eventBuffer = new EventBuffer();
        executor = new ScheduledThreadPoolExecutor(5, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable, "EventHandler-" + relatedControllerName);
            }
        });
    }

    public void setDefaultEventSourceManager(DefaultEventSourceManager defaultEventSourceManager) {
        this.defaultEventSourceManager = defaultEventSourceManager;
    }

    @Override
    public void handleEvent(Event event) {
        try {
            lock.lock();
            log.debug("Received event: {}", event);
            eventBuffer.addEvent(event);
            executeBufferedEvents(event.getRelatedCustomResourceUid());
        } finally {
            lock.unlock();
        }
    }

    private void executeBufferedEvents(String customResourceUid) {
        boolean newEventForResourceId = eventBuffer.containsEvents(customResourceUid);
        boolean controllerUnderExecution = isControllerUnderExecution(customResourceUid);
        Optional<CustomResource> latestCustomResource = customResourceCache.getLatestResource(customResourceUid);

        if (!controllerUnderExecution && newEventForResourceId && latestCustomResource.isPresent()) {
            setUnderExecutionProcessing(customResourceUid);
            ExecutionScope executionScope = new ExecutionScope(
                    eventBuffer.getAndRemoveEventsForExecution(customResourceUid),
                    latestCustomResource.get());
            log.debug("Executing events for custom resource. Scope: {}", executionScope);
            executor.execute(new ExecutionConsumer(executionScope, eventDispatcher, this));
        } else {
            log.debug("Skipping executing controller for resource id: {}. Events in queue: {}." +
                            " Controller in execution: {}. Latest CustomResource present: {}"
                    , customResourceUid, newEventForResourceId, controllerUnderExecution, latestCustomResource.isPresent());
        }
    }

    void eventProcessingFinished(ExecutionScope executionScope, PostExecutionControl postExecutionControl) {
        try {
            lock.lock();
            log.debug("Event processing finished. Scope: {}", executionScope);
            unsetUnderExecution(executionScope.getCustomResourceUid());
            if (containsCustomResourceDeletedEvent(executionScope.getEvents())) {
                cleanupAfterDeletedEvent(executionScope.getCustomResourceUid());
            } else {
                cacheUpdatedResourceIfChanged(executionScope, postExecutionControl);
                executeBufferedEvents(executionScope.getCustomResourceUid());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Here we try to cache the latest resource after an update. The goal is to solve a concurrency issue we've seen:
     * If an execution is finished, where we updated a custom resource, but there are other events already buffered for next
     * execution, we might not get the newest custom resource from CustomResource event source in time. Thus we execute
     * the next batch of events but with a non up to date CR. Here we cache the latest CustomResource from the update
     * execution so we make sure its already used in the up-coming execution.
     *
     * Note that this is an improvement, not a bug fix. This situation can happen naturally, we just make the execution more
     * efficient, and avoid questions about conflicts.
     *
     * Note that without the conditional locking in the cache, there is a very minor chance that we would override an
     * additional change coming from a different client.
     */
    private void cacheUpdatedResourceIfChanged(ExecutionScope executionScope, PostExecutionControl postExecutionControl) {
        if (postExecutionControl.customResourceUpdatedDuringExecution()) {
            CustomResource originalCustomResource = executionScope.getCustomResource();
            CustomResource customResourceAfterExecution = postExecutionControl.getUpdatedCustomResource().get();
            String originalResourceVersion = getVersion(originalCustomResource);

            log.debug("Trying to update resource cache from update response for resource uid: {} new version: {} old version: {}",
                    getUID(originalCustomResource), getVersion(customResourceAfterExecution), getVersion(originalCustomResource));
            this.customResourceCache.cacheResource(customResourceAfterExecution, customResource ->
                    getVersion(customResource).equals(originalResourceVersion)
                            && !originalResourceVersion.equals(getVersion(customResourceAfterExecution))
            );
        }
    }

    private void cleanupAfterDeletedEvent(String customResourceUid) {
        defaultEventSourceManager.cleanup(customResourceUid);
        eventBuffer.cleanup(customResourceUid);
        customResourceCache.cleanup(customResourceUid);
    }

    private boolean isControllerUnderExecution(String customResourceUid) {
        return underProcessing.contains(customResourceUid);
    }

    private void setUnderExecutionProcessing(String customResourceUid) {
        underProcessing.add(customResourceUid);
    }

    private void unsetUnderExecution(String customResourceUid) {
        underProcessing.remove(customResourceUid);
    }
}

