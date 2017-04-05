package org.camunda.tngp.client.event.impl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;
import org.camunda.tngp.client.impl.Loggers;
import org.camunda.tngp.client.task.impl.EventSubscriptionCreationResult;
import org.camunda.tngp.util.CheckedConsumer;
import org.slf4j.Logger;

public abstract class EventSubscription<T extends EventSubscription<T>>
{
    protected static final Logger LOGGER = Loggers.SUBSCRIPTION_LOGGER;

    public static final int STATE_NEW = 0;
    public static final int STATE_OPENING = 1;
    public static final int STATE_OPEN = 2;

    // semantics of closing: subscription is currently open on broker-side and we want to close it and clean up on client side
    public static final int STATE_CLOSING = 3;
    public static final int STATE_CLOSED = 4;

    // semantics of aborting: subscription is closed on broker side and we want to clean up on client side
    public static final int STATE_ABORTING = 5;
    public static final int STATE_ABORTED = 6;

    protected long id;
    protected final EventAcquisition<T> eventAcquisition;
    protected final ManyToManyConcurrentArrayQueue<TopicEventImpl> pendingEvents;
    protected final int capacity;

    /*
     * The channel that events are received on
     */
    protected int receiveChannelId;

    protected final AtomicInteger state = new AtomicInteger(STATE_NEW);
    protected final AtomicInteger eventsInProcessing = new AtomicInteger(0);
    protected CompletableFuture<Void> closeFuture;

    public EventSubscription(EventAcquisition<T> eventAcquisition, int upperBoundCapacity)
    {
        this.eventAcquisition = eventAcquisition;
        this.pendingEvents = new ManyToManyConcurrentArrayQueue<>(upperBoundCapacity);
        this.capacity = pendingEvents.capacity();
    }

    public long getId()
    {
        return id;
    }

    public void setId(long id)
    {
        this.id = id;
    }

    public int getReceiveChannelId()
    {
        return receiveChannelId;
    }

    public void setReceiveChannelId(int receiveChannelId)
    {
        this.receiveChannelId = receiveChannelId;
    }

    public int capacity()
    {
        return capacity;
    }

    public int size()
    {
        return pendingEvents.size();
    }

    public boolean hasPendingEvents()
    {
        return !pendingEvents.isEmpty();
    }

    public boolean hasEventsInProcessing()
    {
        return eventsInProcessing.get() > 0;
    }

    public boolean isOpen()
    {
        return state.get() == STATE_OPEN;
    }

    public boolean isClosing()
    {
        return state.get() == STATE_CLOSING;
    }

    public boolean isClosed()
    {
        final int currentState = state.get();
        return currentState == STATE_CLOSED || currentState == STATE_ABORTED;
    }

    public abstract boolean isManagedSubscription();

    public void close()
    {
        try
        {
            closeAsync().get();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Exception while closing subscription", e);
        }
    }

    public CompletableFuture<Void> closeAsync()
    {
        if (state.compareAndSet(STATE_OPEN, STATE_CLOSING))
        {
            closeFuture = new CompletableFuture<>();
            return closeFuture;
        }
        else
        {
            return CompletableFuture.completedFuture(null);
        }
    }

    public void open()
    {
        try
        {
            openAsync().get();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Exception while opening subscription", e);
        }
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> openAsync()
    {
        if (state.compareAndSet(STATE_NEW, STATE_OPENING))
        {
            return eventAcquisition
                .openSubscriptionAsync((T) this)
                .thenAccept((v) -> state.compareAndSet(STATE_OPENING, STATE_OPEN));

        }
        else
        {
            return CompletableFuture.completedFuture(null);
        }
    }

    public void abortAsync()
    {
        if (state.compareAndSet(STATE_OPEN, STATE_ABORTING))
        {
            eventAcquisition.abort((T) this);
        }
    }

    public void addEvent(TopicEventImpl event)
    {
        final boolean added = this.pendingEvents.offer(event);

        if (!added)
        {
            throw new RuntimeException("Cannot add any more events. Event queue saturated.");
        }
    }

    public abstract int poll();

    @SuppressWarnings("unchecked")
    protected int pollEvents(CheckedConsumer<TopicEventImpl> pollHandler)
    {
        final int currentlyAvailableEvents = size();
        int handledEvents = 0;

        TopicEventImpl event;

        // handledTasks < currentlyAvailableTasks avoids very long cycles that we spend in this method
        // in case the broker continuously produces new tasks
        while (handledEvents < currentlyAvailableEvents && isOpen())
        {
            event = pendingEvents.poll();
            if (event == null)
            {
                break;
            }

            eventsInProcessing.incrementAndGet();
            try
            {
                handledEvents++;

                try
                {
                    pollHandler.accept(event);
                }
                catch (Exception e)
                {
                    onUnhandledEventHandlingException(event, e);
                }
            }
            finally
            {
                eventsInProcessing.decrementAndGet();
            }
        }

        if (handledEvents > 0)
        {
            eventAcquisition.onEventsPolledAsync((T) this, handledEvents);
        }

        return handledEvents;
    }

    protected void onUnhandledEventHandlingException(TopicEventImpl event, Exception e)
    {
        throw new RuntimeException("Exception during handling of event " + event.getEventKey(), e);
    }

    public void onClose()
    {
        state.compareAndSet(STATE_CLOSING, STATE_CLOSED);
        closeFuture.complete(null);
    }

    public void onCloseFailed(Exception e)
    {
        // setting this to closed anyway for now
        state.compareAndSet(STATE_CLOSING, STATE_CLOSED);
        closeFuture.completeExceptionally(e);
    }

    public void onAbort()
    {
        state.compareAndSet(STATE_ABORTING, STATE_ABORTED);
    }

    protected abstract EventSubscriptionCreationResult requestNewSubscription();

    protected abstract void requestSubscriptionClose();

    protected abstract void onEventsPolled(int numEvents);

    public abstract int getTopicId();


}