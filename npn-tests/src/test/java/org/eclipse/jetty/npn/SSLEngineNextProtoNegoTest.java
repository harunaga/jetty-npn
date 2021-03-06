/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.npn;


import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

import org.junit.Assert;
import org.junit.Test;

public class SSLEngineNextProtoNegoTest
{
    private final void setupSSLEngineNPNSuccessfulHandshake(final NextProtoNego.ServerProvider serverProvider,
            final NextProtoNego.ClientProvider clientProvider, AtomicReference<CountDownLatch> latch) throws Exception {
        final SSLContext context = SSLSupport.newSSLContext();

        final int readTimeout = 5000;
        final String data = "data";
        final ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    final SSLEngine sslEngine = context.createSSLEngine();
                    sslEngine.setUseClientMode(false);
                    NextProtoNego.put(sslEngine, serverProvider);
                    ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
                    ByteBuffer decrypted = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

                    SocketChannel socket = server.accept();
                    socket.socket().setSoTimeout(readTimeout);

                    sslEngine.beginHandshake();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());

                    // Read ClientHello
                    socket.read(encrypted);
                    encrypted.flip();
                    SSLEngineResult result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

                    // Generate and write ServerHello
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // Read up to Finished
                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());

                    // With NPN in place, we need one more unwrap() call, that is
                    // not needed with without NPN, for the NextProtocol message
                    if (SSLEngineResult.HandshakeStatus.NEED_UNWRAP == result.getHandshakeStatus())
                    {
                        if (!encrypted.hasRemaining())
                        {
                            encrypted.clear();
                            socket.read(encrypted);
                            encrypted.flip();
                        }
                        result = sslEngine.unwrap(encrypted, decrypted);
                        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    }
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());

                    // Generate and write ChangeCipherSpec
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);
                    // Generate and write Finished
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.FINISHED, result.getHandshakeStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, sslEngine.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // Read data
                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    decrypted.clear();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());

                    // Echo the data back
                    encrypted.clear();
                    decrypted.flip();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // Read re-handshake
                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    decrypted.clear();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    decrypted.clear();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    decrypted.clear();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());

                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.FINISHED, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // Read more data
                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    decrypted.clear();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());

                    // Echo the data back
                    encrypted.clear();
                    decrypted.flip();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // TODO
                    // Re-handshake
//                    sslEngine.beginHandshake();

                    // Close
                    NextProtoNego.remove(sslEngine);
                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    decrypted.clear();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.CLOSED, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
                    encrypted.clear();
                    Assert.assertEquals(-1, socket.read(encrypted));
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.CLOSED, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);
                    socket.close();
                }
                catch (Exception x)
                {
                    x.printStackTrace();
                }
            }
        }.start();

        final SSLEngine sslEngine = context.createSSLEngine();
        sslEngine.setUseClientMode(true);
        NextProtoNego.put(sslEngine, clientProvider);
        ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        ByteBuffer decrypted = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

        SocketChannel client = SocketChannel.open(server.getLocalAddress());
        client.socket().setSoTimeout(readTimeout);

        sslEngine.beginHandshake();
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        // Generate and write ClientHello
        SSLEngineResult result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);

        // Read Server Hello
        while (sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
        {
            encrypted.clear();
            client.read(encrypted);
            encrypted.flip();
            result = sslEngine.unwrap(encrypted, decrypted);
            Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
            if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK)
                sslEngine.getDelegatedTask().run();
        }
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        // Generate and write ClientKeyExchange
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);
        // Generate and write ChangeCipherSpec
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);
        // Generate and write Finished
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        encrypted.flip();
        client.write(encrypted);

        // With NPN in place, we need one more wrap() call, that is
        // not needed with without NPN, for the NexProtocol message
        if (SSLEngineResult.HandshakeStatus.NEED_WRAP == result.getHandshakeStatus())
        {
            encrypted.clear();
            result = sslEngine.wrap(decrypted, encrypted);
            Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
            encrypted.flip();
            client.write(encrypted);
        }
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());

        // Read ChangeCipherSpec
        encrypted.clear();
        client.read(encrypted);
        encrypted.flip();
        decrypted.clear();
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        // Read Finished
        if (!encrypted.hasRemaining())
        {
            encrypted.clear();
            client.read(encrypted);
            encrypted.flip();
        }
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.FINISHED, result.getHandshakeStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, sslEngine.getHandshakeStatus());

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // Now try to write real data to see if it works
        encrypted.clear();
        result = sslEngine.wrap(ByteBuffer.wrap(data.getBytes("UTF-8")), encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);

        // Read echo
        encrypted.clear();
        client.read(encrypted);
        encrypted.flip();
        decrypted.clear();
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());

        decrypted.flip();
        Assert.assertEquals(data, Charset.forName("UTF-8").decode(decrypted).toString());

        // Perform a re-handshake, and verify that NPN does not trigger
        latch.set(new CountDownLatch(4));
        sslEngine.beginHandshake();
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);

        encrypted.clear();
        client.read(encrypted);
        encrypted.flip();
        decrypted.clear();
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
        sslEngine.getDelegatedTask().run();
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);

        encrypted.clear();
        client.read(encrypted);
        encrypted.flip();
        decrypted.clear();
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        if (!encrypted.hasRemaining())
        {
            encrypted.clear();
            client.read(encrypted);
            encrypted.flip();
        }
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.FINISHED, result.getHandshakeStatus());

        // Re-handshake completed, check NPN was not invoked
        Assert.assertEquals(4, latch.get().getCount());

        // Write more data
        encrypted.clear();
        result = sslEngine.wrap(ByteBuffer.wrap(data.getBytes("UTF-8")), encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);

        // Read echo
        encrypted.clear();
        client.read(encrypted);
        encrypted.flip();
        decrypted.clear();
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());

        decrypted.flip();
        Assert.assertEquals(data, Charset.forName("UTF-8").decode(decrypted).toString());

        // Close
        NextProtoNego.remove(sslEngine);
        sslEngine.closeOutbound();
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.CLOSED, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);
        client.shutdownOutput();
        encrypted.clear();
        client.read(encrypted);
        encrypted.flip();
        decrypted.clear();
        result = sslEngine.unwrap(encrypted, decrypted);
        Assert.assertSame(SSLEngineResult.Status.CLOSED, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, result.getHandshakeStatus());
        client.close();

        server.close();
    
    }

    @Test
    public void testSSLEngine() throws Exception
    {
        NextProtoNego.debug = true;
        final String protocolName = "test";
        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(4));
        setupSSLEngineNPNSuccessfulHandshake(new NextProtoNego.ServerProvider()
        {
            @Override
            public void unsupported()
            {
            }

            @Override
            public List<String> protocols()
            {
                latch.get().countDown();
                return Arrays.asList(protocolName);
            }

            @Override
            public void protocolSelected(String protocol)
            {
                Assert.assertEquals(protocolName, protocol);
                latch.get().countDown();
            }
        }, new NextProtoNego.ClientProvider()
        {
            @Override
            public boolean supports()
            {
                latch.get().countDown();
                return true;
            }

            @Override
            public void unsupported()
            {
            }

            @Override
            public String selectProtocol(List<String> protocols)
            {
                Assert.assertEquals(1, protocols.size());
                String protocol = protocols.get(0);
                Assert.assertEquals(protocolName, protocol);
                latch.get().countDown();
                return protocol;
            }
        }, latch);
    }

    @Test
    public void testSSLEngineClientReturnsEmptySelectedProtocol() throws Exception
    {
        NextProtoNego.debug = true;
        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(4));
        
        this.setupSSLEngineNPNSuccessfulHandshake(new NextProtoNego.ServerProvider()
        {
            @Override
            public void unsupported()
            {
            }

            @Override
            public List<String> protocols()
            {
                latch.get().countDown();
                return Arrays.asList("test");
            }

            @Override
            public void protocolSelected(String protocol)
            {
                Assert.assertNull(protocol);
                latch.get().countDown();
            }
        }, new NextProtoNego.ClientProvider()
        {
            @Override
            public boolean supports()
            {
                latch.get().countDown();
                return true;
            }

            @Override
            public void unsupported()
            {
            }

            @Override
            public String selectProtocol(List<String> protocols)
            {
                latch.get().countDown();
                return null;
            }
        }, latch);
    }

    @Test(expected = RuntimeException.class)
    public void testSSLEngineNoCompatibleProtocolsClientHandshakeFailure() throws Exception
    {
        NextProtoNego.debug = true;

        final SSLContext context = SSLSupport.newSSLContext();

        final int readTimeout = 5000;
        final String protocolName = "test";
        final ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    final SSLEngine sslEngine = context.createSSLEngine();
                    sslEngine.setUseClientMode(false);
                    NextProtoNego.put(sslEngine, new NextProtoNego.ServerProvider()
                    {
                        @Override
                        public void unsupported()
                        {
                        }

                        @Override
                        public List<String> protocols()
                        {
                            return Arrays.asList(protocolName);
                        }

                        @Override
                        public void protocolSelected(String protocol)
                        {
                            Assert.assertEquals(protocolName, protocol);
                        }
                    });
                    ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
                    ByteBuffer decrypted = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

                    SocketChannel socket = server.accept();
                    socket.socket().setSoTimeout(readTimeout);

                    sslEngine.beginHandshake();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());

                    // Read ClientHello
                    socket.read(encrypted);
                    encrypted.flip();
                    SSLEngineResult result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

                    // Generate and write ServerHello
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // Read up to Finished
                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());

                    // With NPN in place, we need one more unwrap() call, that is
                    // not needed with without NPN, for the NextProtocol message
                    if (SSLEngineResult.HandshakeStatus.NEED_UNWRAP == result.getHandshakeStatus())
                    {
                        if (!encrypted.hasRemaining())
                        {
                            encrypted.clear();
                            socket.read(encrypted);
                            encrypted.flip();
                        }
                        result = sslEngine.unwrap(encrypted, decrypted);
                        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    }
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());

                    // Generate and write ChangeCipherSpec
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);
                    // Generate and write Finished
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.FINISHED, result.getHandshakeStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, sslEngine.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // Close
                    NextProtoNego.remove(sslEngine);
                    socket.write(encrypted);
                    socket.close();
                }
                catch (Exception x)
                {
                    x.printStackTrace();
                }
            }
        }.start();

        final SSLEngine sslEngine = context.createSSLEngine();
        sslEngine.setUseClientMode(true);
        NextProtoNego.put(sslEngine, new NextProtoNego.ClientProvider()
        {
            @Override
            public boolean supports()
            {
                return true;
            }

            @Override
            public void unsupported()
            {
            }

            @Override
            public String selectProtocol(List<String> protocols)
            {
                throw new RuntimeException("Client says nothing is good enough");
            }
        });
        ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        ByteBuffer decrypted = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

        SocketChannel client = SocketChannel.open(server.getLocalAddress());
        client.socket().setSoTimeout(readTimeout);

        sslEngine.beginHandshake();
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        // Generate and write ClientHello
        SSLEngineResult result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);

        // Read Server Hello
        while (sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
        {
            encrypted.clear();
            client.read(encrypted);
            encrypted.flip();
            result = sslEngine.unwrap(encrypted, decrypted);
            Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
            if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK)
                sslEngine.getDelegatedTask().run();
        }
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        // Generate and write ClientKeyExchange
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);

        // Close
        NextProtoNego.remove(sslEngine);
        sslEngine.closeOutbound();
        client.close();

        server.close();
    }

    @Test(expected = RuntimeException.class)
    public void testSSLEngineNoCompatibleProtocolsServerHandshakeFailure() throws Exception
    {
        NextProtoNego.debug = true;

        final SSLContext context = SSLSupport.newSSLContext();

        final int readTimeout = 5000;
        final String protocolName = "test";
        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(4));
        final ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("localhost", 0));
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    final SSLEngine sslEngine = context.createSSLEngine();
                    sslEngine.setUseClientMode(false);
                    NextProtoNego.put(sslEngine, new NextProtoNego.ServerProvider()
                    {
                        @Override
                        public void unsupported()
                        {
                        }

                        @Override
                        public List<String> protocols()
                        {
                            latch.get().countDown();
                            return Arrays.asList(protocolName);
                        }

                        @Override
                        public void protocolSelected(String protocol)
                        {
                            Assert.assertEquals(protocolName, protocol);
                            latch.get().countDown();
                        }
                    });
                    ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
                    ByteBuffer decrypted = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

                    SocketChannel socket = server.accept();
                    socket.socket().setSoTimeout(readTimeout);

                    sslEngine.beginHandshake();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());

                    // Read ClientHello
                    socket.read(encrypted);
                    encrypted.flip();
                    SSLEngineResult result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

                    // Generate and write ServerHello
                    encrypted.clear();
                    result = sslEngine.wrap(decrypted, encrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
                    encrypted.flip();
                    socket.write(encrypted);

                    // Read up to Finished
                    encrypted.clear();
                    socket.read(encrypted);
                    encrypted.flip();
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());
                    sslEngine.getDelegatedTask().run();
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, sslEngine.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
                    if (!encrypted.hasRemaining())
                    {
                        encrypted.clear();
                        socket.read(encrypted);
                        encrypted.flip();
                    }
                    result = sslEngine.unwrap(encrypted, decrypted);
                    Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());

                    // With NPN in place, we need one more unwrap() call, that is
                    // not needed with without NPN, for the NextProtocol message
                    if (SSLEngineResult.HandshakeStatus.NEED_UNWRAP == result.getHandshakeStatus())
                    {
                        if (!encrypted.hasRemaining())
                        {
                            encrypted.clear();
                            socket.read(encrypted);
                            encrypted.flip();
                        }
                        result = sslEngine.unwrap(encrypted, decrypted);
                        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
                    }
                    Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());

                    // Close
                    NextProtoNego.remove(sslEngine);
                    socket.close();
                }
                catch (Exception x)
                {
                    x.printStackTrace();
                }
            }
        }.start();

        final SSLEngine sslEngine = context.createSSLEngine();
        sslEngine.setUseClientMode(true);
        NextProtoNego.put(sslEngine, new NextProtoNego.ClientProvider()
        {
            @Override
            public boolean supports()
            {
                latch.get().countDown();
                return true;
            }

            @Override
            public void unsupported()
            {
            }

            @Override
            public String selectProtocol(List<String> protocols)
            {
                Assert.assertEquals(1, protocols.size());
                latch.get().countDown();
                throw new RuntimeException("Server says nothing is good enough");
            }
        });
        ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        ByteBuffer decrypted = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());

        SocketChannel client = SocketChannel.open(server.getLocalAddress());
        client.socket().setSoTimeout(readTimeout);

        sslEngine.beginHandshake();
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        // Generate and write ClientHello
        SSLEngineResult result = sslEngine.wrap(decrypted, encrypted);
        Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        encrypted.flip();
        client.write(encrypted);

        // Read Server Hello
        while (sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
        {
            encrypted.clear();
            client.read(encrypted);
            encrypted.flip();
            result = sslEngine.unwrap(encrypted, decrypted);
            Assert.assertSame(SSLEngineResult.Status.OK, result.getStatus());
            if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK)
                sslEngine.getDelegatedTask().run();
        }
        Assert.assertSame(SSLEngineResult.HandshakeStatus.NEED_WRAP, sslEngine.getHandshakeStatus());

        // Generate and write ClientKeyExchange
        encrypted.clear();
        result = sslEngine.wrap(decrypted, encrypted);

        // Close
        NextProtoNego.remove(sslEngine);
        sslEngine.closeOutbound();
        client.close();

        server.close();
    }
}
