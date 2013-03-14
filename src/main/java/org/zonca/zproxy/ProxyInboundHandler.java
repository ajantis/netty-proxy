package org.zonca.zproxy;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.util.concurrent.RateLimiter;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.*;

public class ProxyInboundHandler extends SimpleChannelUpstreamHandler {
	private final ClientSocketChannelFactory cf;
	private final String remoteHost;
	private final int remotePort;

    private final RateConfigHolder rateConfigHolder = RateConfigHolder.INSTANCE;
    private final Map<String, RateLimiter> rateConfig = rateConfigHolder.getRateConfig();

    private final Pattern tenantPattern = Pattern.compile("[^/]*/api/([^/]*).*");

	// This lock guards against the race condition that overrides the
	// OP_READ flag incorrectly.
	// See the related discussion: http://markmail.org/message/x7jc6mqx6ripynqf
	final Object trafficLock = new Object();

	private volatile Channel outboundChannel;

	public ProxyInboundHandler(ClientSocketChannelFactory cf,
			String remoteHost, int remotePort) {
		this.cf = cf;
		this.remoteHost = remoteHost;
		this.remotePort = remotePort;
	}

	@Override
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e)
			throws Exception {
		// Suspend incoming traffic until connected to the remote host.
		final Channel inboundChannel = e.getChannel();
		inboundChannel.setReadable(false);

		// Start the connection attempt.
		ClientBootstrap cb = new ClientBootstrap(cf);
        cb.getPipeline()
                .addLast("decoder", new HttpResponseDecoder());
        cb.getPipeline()
                .addLast("encoder", new HttpRequestEncoder());
		cb.getPipeline()
				.addLast("handler", new OutboundHandler(e.getChannel()));
		ChannelFuture f = cb.connect(new InetSocketAddress(remoteHost,
				remotePort));

		outboundChannel = f.getChannel();
		f.addListener(new ChannelFutureListener() {
			public void operationComplete(ChannelFuture future)
					throws Exception {
				if (future.isSuccess()) {
					// Connection attempt succeeded:
					// Begin to accept incoming traffic.
					inboundChannel.setReadable(true);
				} else { // Close the connection if the connection attempt has
							// failed.
					inboundChannel.close();
				}
			}
		});
	}
	
	@Override
    public void messageReceived(ChannelHandlerContext ctx, final MessageEvent e)
            throws Exception {
        //System.out.println(">>> " + ChannelBuffers.hexDump(msg));
        synchronized (trafficLock) {
            final HttpRequest httpRequest = (HttpRequest)e.getMessage();
            System.out.println(String.format("%s -> %s", httpRequest.getMethod().getName(), httpRequest.getUri()));
            final String tenant = extractTenantId(httpRequest.getUri());

            if (tenant != null && rateConfig.get(tenant) != null){
                final RateLimiter limiter = rateConfig.get(tenant);
                if (limiter.tryAcquire(1, 2, TimeUnit.SECONDS)){
                    long startTime = System.currentTimeMillis();
                    System.out.println("Acquiring conn for tenant " + tenant + ". Start: " + startTime);

                    limiter.acquire();

                    long endtime = System.currentTimeMillis();
                    System.out.println("Acquired conn for tenant " + tenant + ". Took: " + (endtime - startTime) + " ms");
                } else
                    return;
            }

            outboundChannel.write(httpRequest);
            // If outboundChannel is saturated, do not read until notified in
            // OutboundHandler.channelInterestChanged().
            if (!outboundChannel.isWritable()) {
                e.getChannel().setReadable(false);
            }
        }
    }

    @Override
    public void channelInterestChanged(ChannelHandlerContext ctx,
            ChannelStateEvent e) throws Exception {
        // If inboundChannel is not saturated anymore, continue accepting
        // the incoming traffic from the outboundChannel.
        synchronized (trafficLock) {
            if (e.getChannel().isWritable()) {
                if (outboundChannel != null) {
                    outboundChannel.setReadable(true);
                }
            }
        }
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
            throws Exception {
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
            throws Exception {
        e.getCause().printStackTrace();
        closeOnFlush(e.getChannel());
    }

    private class OutboundHandler extends SimpleChannelUpstreamHandler {

        private final Channel inboundChannel;

        OutboundHandler(Channel inboundChannel) {
            this.inboundChannel = inboundChannel;
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, final MessageEvent e)
                throws Exception {
            //System.out.println("<<< " + ChannelBuffers.hexDump(msg));
            synchronized (trafficLock) {
                if(e.getMessage() instanceof HttpResponse){
                    HttpResponse msg = (HttpResponse) e.getMessage();
                    inboundChannel.write(msg);
                } else {
                    HttpChunk chunk = (HttpChunk)e.getMessage();
                    inboundChannel.write(chunk);
                }
                // If inboundChannel is saturated, do not read until notified in
                // HexDumpProxyInboundHandler.channelInterestChanged().
                if (!inboundChannel.isWritable()) {
                    e.getChannel().setReadable(false);
                }
            }
        }

        @Override
        public void channelInterestChanged(ChannelHandlerContext ctx,
                ChannelStateEvent e) throws Exception {
            // If outboundChannel is not saturated anymore, continue accepting
            // the incoming traffic from the inboundChannel.
            synchronized (trafficLock) {
                if (e.getChannel().isWritable()) {
                    inboundChannel.setReadable(true);
                }
            }
        }

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
                throws Exception {
            closeOnFlush(inboundChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
                throws Exception {
            e.getCause().printStackTrace();
            closeOnFlush(e.getChannel());
        }
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    static void closeOnFlush(Channel ch) {
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private String extractTenantId(String uri){
        final Matcher m = tenantPattern.matcher(uri);
        if(m.matches()){
            String tenant = m.group(1);
            System.out.println("Tenant is found: " + tenant);
            return tenant;
        }
        return null;
    }
}
