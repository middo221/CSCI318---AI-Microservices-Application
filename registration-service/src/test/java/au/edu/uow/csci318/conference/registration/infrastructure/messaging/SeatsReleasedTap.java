package au.edu.uow.csci318.conference.registration.infrastructure.messaging;

import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lets a test read what registration publishes on {@code seats-released}.
 *
 * <p>{@link OutputDestination} cannot, because registration also consumes that topic (FR-10). The
 * test binder gives a destination's producer and consumer one shared channel, and
 * {@code OutputDestination} attaches its queue only if that channel has no subscriber when the
 * producer is provisioned. Registration's own binding subscribes at start-up, while
 * {@code StreamBridge} provisions the producer lazily on its first send, so the queue for
 * {@code seats-released} is never attached: every read of it returns {@code null}, and an assertion
 * that nothing was published passes whatever happens.
 *
 * <p>This tap reads the same messages one hop later, on the input channel of the
 * {@code seatsReleased-in-0} binding, which every message on the destination reaches with its payload
 * still serialised and its key header intact. The application context — and so the channel —
 * outlives a test, so {@link #close()} it after each one.
 */
public final class SeatsReleasedTap implements ChannelInterceptor, AutoCloseable {

    private final AbstractMessageChannel channel;
    private final BlockingQueue<Message<?>> published = new LinkedBlockingQueue<>();

    private SeatsReleasedTap(AbstractMessageChannel channel) {
        this.channel = channel;
    }

    public static SeatsReleasedTap attach(ApplicationContext context) {
        SeatsReleasedTap tap = new SeatsReleasedTap(
                context.getBean("seatsReleased-in-0", AbstractMessageChannel.class));
        tap.channel.addInterceptor(tap);
        return tap;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel target) {
        published.offer(message);
        return message;
    }

    /** The next {@code seats-released} published, or {@code null} if none arrives in time. */
    @SuppressWarnings("unchecked")
    public Message<byte[]> receive(long timeoutMillis) {
        try {
            return (Message<byte[]>) published.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Forgets what has been published so far, as {@link OutputDestination#clear()} does. */
    public void clear() {
        published.clear();
    }

    @Override
    public void close() {
        channel.removeInterceptor(this);
    }
}
