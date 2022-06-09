package io.engytita.proxy.handler.protocol.http2;

import static io.engytita.proxy.handler.protocol.http2.Http2FrameWrapper.frameWrapper;
import static io.engytita.proxy.util.LogWrappers.format;
import static io.netty.handler.logging.LogLevel.DEBUG;
import static io.netty.util.ReferenceCountUtil.touch;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.engytita.proxy.ConnectionContext;
import io.engytita.proxy.exception.ProxyException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;

public class Http2BackendHandler
      extends ChannelDuplexHandler
      implements Http2FrameListener {

   private static final Logger LOGGER = LoggerFactory.getLogger(Http2BackendHandler.class);

   private ConnectionContext connectionContext;
   private Http2ConnectionHandler http2ConnectionHandler;

   private ChannelPromise ready;
   private AtomicInteger currentStreamId = new AtomicInteger(1);
   private ConcurrentHashMap<Integer, Integer> streams = new ConcurrentHashMap<>();
   private ConcurrentHashMap<Integer, Integer> inverse = new ConcurrentHashMap<>();

   public Http2BackendHandler(ConnectionContext connectionContext) {
      this.connectionContext = connectionContext;
   }

   @Override
   public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      LOGGER.debug("{} : handlerAdded", connectionContext);

      Http2Connection http2Connection = new DefaultHttp2Connection(false);
      http2ConnectionHandler = new Http2ConnectionHandlerBuilder()
            .connection(http2Connection)
            .frameListener(this)
            .frameLogger(new Http2FrameLogger(DEBUG))
            .build();
      ctx.pipeline()
            .addBefore(ctx.name(), null, http2ConnectionHandler);

      ready = ctx.newPromise();
   }

   @Override
   public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
         throws Exception {
      if (!(msg instanceof Http2FrameWrapper<?> frame)) {
         ctx.write(msg, promise);
         return;
      }
      if (ready.isSuccess()) {
         frame.write(ctx, http2ConnectionHandler.encoder(), getUpstreamStreamId(frame.streamId()),
               promise);
         ctx.flush();
      } else {
         ready.addListener(ignore -> {
            frame.write(ctx, http2ConnectionHandler.encoder(), getUpstreamStreamId(frame.streamId()),
                  promise);
            ctx.flush();
         });
      }
   }

   @Override
   public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);

      LOGGER.debug("{} : channelInactive", connectionContext);
      if (!ready.isDone()) {
         ready.setFailure(new ProxyException("Channel was closed"));
      }
      connectionContext.clientChannel().close();
   }

   @Override
   public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      LOGGER.debug("{} : exceptionCaught with {}", connectionContext, cause.getMessage());
      ctx.close();
   }

   @Override
   public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
                         boolean endOfStream) {
      int originStreamId = getOriginStreamId(streamId);
      int processed = data.readableBytes() + padding;
      Http2DataFrameWrapper frame = frameWrapper(originStreamId,
            new DefaultHttp2DataFrame(data.copy(), endOfStream, padding));
      connectionContext.clientChannel().writeAndFlush(touch(frame,
            format("%s context=%s", frame, connectionContext)));
      return processed;
   }

   @Override
   public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                             int padding, boolean endOfStream) {
      int originStreamId = getOriginStreamId(streamId);
      connectionContext.clientChannel().writeAndFlush(
            frameWrapper(originStreamId, new DefaultHttp2HeadersFrame(headers, endOfStream, padding)));
   }

   @Override
   public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                             int streamDependency, short weight, boolean exclusive, int padding, boolean endOfStream) {
      int originStreamId = getOriginStreamId(streamId);
      connectionContext.clientChannel().writeAndFlush(
            frameWrapper(originStreamId, new DefaultHttp2HeadersFrame(headers, endOfStream, padding)));
   }

   @Override
   public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency,
                              short weight, boolean exclusive) {
   }

   @Override
   public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
      int originStreamId = getOriginStreamId(streamId);
      connectionContext.clientChannel().writeAndFlush(
            frameWrapper(originStreamId, new DefaultHttp2ResetFrame(errorCode)));
   }

   @Override
   public void onSettingsAckRead(ChannelHandlerContext ctx) {
   }

   @Override
   public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
      ready.setSuccess();

      connectionContext.clientChannel().writeAndFlush(
            frameWrapper(0,
                  new DefaultHttp2SettingsFrame(settings)));
   }

   @Override
   public void onPingRead(ChannelHandlerContext ctx, long data) {
   }

   @Override
   public void onPingAckRead(ChannelHandlerContext ctx, long data) {
   }

   @Override
   public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                 Http2Headers headers, int padding) {
   }

   @Override
   public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode,
                            ByteBuf debugData) {
   }

   @Override
   public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
      int originStreamId = getOriginStreamId(streamId);
      connectionContext.clientChannel().writeAndFlush(
            frameWrapper(originStreamId,
                  new DefaultHttp2WindowUpdateFrame(windowSizeIncrement)));
   }

   @Override
   public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                              Http2Flags flags, ByteBuf payload) {
   }

   private int getUpstreamStreamId(int streamId) {
      if (streamId == 0) {
         return streamId;
      }
      Integer i = streams.computeIfAbsent(streamId, ignore -> currentStreamId.getAndAdd(2));
      inverse.putIfAbsent(i, streamId);
      return i;
   }

   private int getOriginStreamId(int streamId) {
      if (streamId == 0) {
         return streamId;
      }
      if (!inverse.containsKey(streamId)) {
         throw new IllegalStateException("No stream found: " + streamId);
      }
      return inverse.get(streamId);
   }
}