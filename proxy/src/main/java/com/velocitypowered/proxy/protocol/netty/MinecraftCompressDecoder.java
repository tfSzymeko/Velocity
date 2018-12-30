package com.velocitypowered.proxy.protocol.netty;

import static com.velocitypowered.proxy.protocol.util.NettyPreconditions.checkFrame;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.MoreByteBufUtils;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;

public class MinecraftCompressDecoder extends MessageToMessageDecoder<ByteBuf> {

  private static final int MAXIMUM_INITIAL_BUFFER_SIZE = 65536; // 64KiB

  private final int threshold;
  private final VelocityCompressor compressor;

  public MinecraftCompressDecoder(int threshold, VelocityCompressor compressor) {
    this.threshold = threshold;
    this.compressor = compressor;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    int expectedUncompressedSize = ProtocolUtils.readVarInt(in);
    if (expectedUncompressedSize == 0) {
      // Strip the now-useless uncompressed size, this message is already uncompressed.
      out.add(in.retainedSlice());
      in.skipBytes(in.readableBytes());
      return;
    }

    checkFrame(expectedUncompressedSize >= threshold,
        "Uncompressed size %s is greater than threshold %s",
        expectedUncompressedSize, threshold);
    ByteBuf compatibleIn = MoreByteBufUtils.ensureCompatible(ctx.alloc(), compressor, in);
    ByteBuf uncompressed = ctx.alloc().directBuffer(Math.min(expectedUncompressedSize,
        MAXIMUM_INITIAL_BUFFER_SIZE));
    try {
      compressor.inflate(compatibleIn, uncompressed);
      checkFrame(expectedUncompressedSize == uncompressed.readableBytes(),
          "Mismatched compression sizes (got %s, expected %s)",
          uncompressed.readableBytes(), expectedUncompressedSize);
      out.add(uncompressed);
    } catch (Exception e) {
      uncompressed.release();
      throw e;
    } finally {
      compatibleIn.release();
    }
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    compressor.dispose();
  }
}
