package io.engytita.proxy.handler.protocol.tls;

import static io.netty.handler.ssl.ApplicationProtocolNames.HTTP_1_1;
import static java.lang.String.format;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.engytita.proxy.ConnectionContext;
import io.engytita.proxy.Protocols;
import io.engytita.proxy.ProxyMaster;
import io.engytita.proxy.exception.ProxyException;
import io.engytita.proxy.tls.TlsUtil;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;

public class TlsBackendHandler extends ChannelDuplexHandler {
   private static final Logger LOGGER = LoggerFactory.getLogger(TlsBackendHandler.class);
   private final List<Object> pendings = new ArrayList<>();
   private ProxyMaster master;
   private ConnectionContext connectionContext;

   public TlsBackendHandler(ProxyMaster master, ConnectionContext connectionContext) {
      this.master = master;
      this.connectionContext = connectionContext;
   }

   @Override
   public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      LOGGER.debug("{} : handlerAdded", connectionContext);

      connectionContext.tlsCtx().protocolsPromise().addListener(future -> {
         if (!future.isSuccess()) {
            ctx.close();
         } else if (connectionContext.tlsCtx().isEnabled()) {
            configSsl(ctx);
         } else if (connectionContext.tlsCtx().protocolPromise().isSuccess()) {
            configureProtocol(ctx, connectionContext.tlsCtx().protocol());
         } else if (connectionContext.tlsCtx().protocolPromise().isDone()) {
            ctx.close();
         } else {
            connectionContext.tlsCtx().protocolPromise().addListener(protocolFuture -> {
               if (protocolFuture.isSuccess()) {
                  configureProtocol(ctx, connectionContext.tlsCtx().protocol());
               } else {
                  ctx.close();
               }
            });
         }
      });
   }

   @Override
   public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
      LOGGER.debug("{} : handlerRemoved", connectionContext);

      flushPendings(ctx);
   }

   @Override
   public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
         throws Exception {
      synchronized (pendings) {
         pendings.add(msg);
      }
      if (ctx.isRemoved()) {
         flushPendings(ctx);
      }
   }

   @Override
   public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      LOGGER.debug("{} : channelInactive", connectionContext);
      connectionContext.clientChannel().close();
      synchronized (pendings) {
         pendings.forEach(ReferenceCountUtil::release);
      }
      ctx.fireChannelInactive();
   }

   @Override
   public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      LOGGER.error(format("%s : exceptionCaught with %s",
                  connectionContext, cause.getMessage()),
            cause);
      ctx.close();
   }

   private SslHandler sslHandler(ByteBufAllocator alloc) throws SSLException {
      return TlsUtil.ctxForClient(connectionContext)
            .newHandler(alloc, connectionContext.getServerAddr().getHost(),
                  connectionContext.getServerAddr().getPort());
   }

   private void flushPendings(ChannelHandlerContext ctx) {
      synchronized (pendings) {
         Iterator<Object> iterator = pendings.iterator();
         while (iterator.hasNext()) {
            ctx.write(iterator.next());
            iterator.remove();
         }
         ctx.flush();
      }
   }

   private void configureProtocol(ChannelHandlerContext ctx, String protocol) {
      try {
         ctx.pipeline().replace(this, null, connectionContext.provider().backendHandler(protocol));
      } catch (ProxyException e) {
         LOGGER.error("{} : Unsupported protocol", connectionContext);
         ctx.close();
      }
   }

   /**
    * Configure for ssl.
    *
    * @param ctx the channel handler context
    * @throws SSLException if ssl failure
    */
   private void configSsl(ChannelHandlerContext ctx) throws SSLException {
      SslHandler sslHandler = sslHandler(ctx.alloc());
      ctx.pipeline()
            .addBefore(ctx.name(), null, sslHandler)
            .addBefore(ctx.name(), null, new AlpnHandler(ctx, getFallbackProtocol()));
   }

   private String getFallbackProtocol() {
      if (connectionContext.tlsCtx().isNegotiated()) {
         return connectionContext.tlsCtx().protocol();
      }
      if (connectionContext.tlsCtx().protocolsPromise().isSuccess()
            && connectionContext.tlsCtx().protocols() != null
            && connectionContext.tlsCtx().protocols().contains(HTTP_1_1)) {
         return HTTP_1_1;
      }
      return Protocols.FORWARD;
   }

   private class AlpnHandler extends ApplicationProtocolNegotiationHandler {
      private ChannelHandlerContext tlsCtx;

      private AlpnHandler(ChannelHandlerContext tlsCtx, String fallbackProtocol) {
         super(fallbackProtocol);
         this.tlsCtx = tlsCtx;
      }

      @Override
      protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
         if (!connectionContext.tlsCtx().isNegotiated()) {
            connectionContext.tlsCtx().protocolPromise().setSuccess(protocol);
         }
         if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            configureProtocol(tlsCtx, Protocols.HTTP_1);
         } else if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            configureProtocol(tlsCtx, Protocols.HTTP_2);
         } else {
            configureProtocol(tlsCtx, Protocols.FORWARD);
         }
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
         super.exceptionCaught(ctx, cause);
         if (!connectionContext.tlsCtx().isNegotiated()) {
            connectionContext.tlsCtx().protocolPromise().setFailure(cause);
         }
      }
   }
}
