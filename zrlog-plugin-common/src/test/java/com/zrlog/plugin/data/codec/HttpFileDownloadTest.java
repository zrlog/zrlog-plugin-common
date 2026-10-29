package com.zrlog.plugin.data.codec;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.client.HttpClientUtils;
import com.zrlog.plugin.common.SecurityUtils;
import com.zrlog.plugin.common.type.HttpMethod;
import com.zrlog.plugin.type.ActionType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class HttpFileDownloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void downloadsFileBeyondJsonLimitAndReleasesReceivedFile() throws Exception {
        byte[] content = new byte[5 * 1024 * 1024];
        content[content.length - 1] = (byte) 255;
        Path receivedDirectory = Files.createTempDirectory("http-file-received-");
        Path received = receivedDirectory.resolve("archive.zip");
        Files.write(received, content);
        AtomicReference<BaseHttpRequestInfo> request = new AtomicReference<>();
        try (Session host = new Session((session, packet) -> {
            request.set(new Gson().fromJson(packet.getDataStr(), BaseHttpRequestInfo.class));
            MsgPacket response = fileResponse(packet, receivedDirectory, received);
            session.dispose(response, response::releaseFilePayload);
        })) {
            Path target = temporary.newFile().toPath();
            HttpClientUtils.downloadToFile("http://host.invalid/file.zip", target, content.length,
                    Collections.singletonMap("Cookie", "private"), host, Duration.ofSeconds(1));
            assertArrayEquals(content, Files.readAllBytes(target));
            assertEquals(HttpMethod.GET, request.get().getHttpMethod());
            assertTrue(request.get().isResponseBodyAsFile());
            assertEquals(Long.valueOf(content.length), request.get().getMaxResponseBodyBytes());
            assertFalse(Files.exists(received));
            assertFalse(Files.exists(receivedDirectory));
            assertTrue(host.getPipeMap().isEmpty());
        } finally {Files.deleteIfExists(received); Files.deleteIfExists(receivedDirectory);}
    }

    @Test public void rejectsOversizedFileAndPreservesDestinationWhileReleasingLease() throws Exception {
        Path directory = Files.createTempDirectory("http-file-too-large-");
        Path received = directory.resolve("large.zip");
        Files.write(received, new byte[]{1,2,3});
        Path target = temporary.newFile().toPath();
        byte[] previous = {9}; Files.write(target, previous);
        try (Session host = new Session((session, packet) -> {
            MsgPacket response = fileResponse(packet, directory, received);
            session.dispose(response, response::releaseFilePayload);
        })) {
            assertThrows(IOException.class, () -> HttpClientUtils.downloadToFile("http://host.invalid/file", target,
                    2, null, host, Duration.ofSeconds(1)));
            assertArrayEquals(previous, Files.readAllBytes(target));
            assertFalse(Files.exists(directory));
            assertTrue(host.getPipeMap().isEmpty());
        } finally {Files.deleteIfExists(received); Files.deleteIfExists(directory);}
    }

    @Test public void rejectsOldCoreJsonAndHttpErrorsWithoutReplacingDestination() throws Exception {
        for (int status : new int[]{200,302,500}) {
            try (Session host = new Session((session, packet) -> {
                HttpResponseInfo response = new HttpResponseInfo(); response.setStatusCode(status);
                response.setResponseBody(new byte[]{1,2});
                session.dispose(new MsgPacket(response, ContentType.JSON, MsgPacketStatus.RESPONSE_SUCCESS,
                        packet.getMsgId(), packet.getMethodStr()));
            })) {
                Path target = temporary.newFile().toPath(); Files.write(target,new byte[]{9});
                IOException error = assertThrows(IOException.class, () -> HttpClientUtils.downloadToFile(
                        "http://host.invalid/file", target, 64, null, host, Duration.ofSeconds(1)));
                assertTrue(error.getMessage(), error.getMessage().contains(status == 200 ? "upgrade plugin-core" : "HTTP " + status));
                assertArrayEquals(new byte[]{9}, Files.readAllBytes(target));
                assertTrue(host.getPipeMap().isEmpty());
            }
        }
    }

    @Test public void releasesRequestOnTimeoutAndReportsClosedSessionAsIoFailure() throws Exception {
        Path target = temporary.newFile().toPath();
        Files.write(target, new byte[]{9});
        try (Session host = new Session((session, packet) -> { })) {
            assertThrows(IOException.class, () -> HttpClientUtils.downloadToFile("http://host.invalid/file",
                    target, 64, null, host, Duration.ofMillis(10)));
            assertTrue(host.getPipeMap().isEmpty());
            host.close();
            assertThrows(IOException.class, () -> HttpClientUtils.downloadToFile("http://host.invalid/file",
                    target, 64, null, host, Duration.ofSeconds(1)));
            assertTrue(host.getPipeMap().isEmpty());
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(target));
        }
    }

    @Test public void rejectsInvalidLimitsWithoutSendingARequest() throws Exception {
        try (Session host = new Session((session, packet) -> fail("invalid request was sent"))) {
            Path target = temporary.newFile().toPath();
            for (long limit : new long[]{0, -1, HttpClientUtils.MAX_FILE_DOWNLOAD_BYTES + 1}) {
                assertThrows(IllegalArgumentException.class, () -> HttpClientUtils.downloadToFile(
                        "http://host.invalid/file", target, limit, null, host, Duration.ofSeconds(1)));
            }
            assertTrue(host.getPipeMap().isEmpty());
        }
    }

    private static MsgPacket fileResponse(MsgPacket request, Path directory, Path file) throws Exception {
        FileDesc desc = new FileDesc(); desc.setFileName(file.getFileName().toString());
        String checksum;
        try(InputStream input=Files.newInputStream(file)){checksum=SecurityUtils.md5(input);}
        MsgPacket response = new MsgPacket();
        response.setContentType(ContentType.FILE); response.setStatus(MsgPacketStatus.RESPONSE_SUCCESS);
        response.setMethodStr(request.getMethodStr()); response.setMsgId(request.getMsgId());
        response.setFilePayload(FilePacketPayload.received(file.toFile(),directory,desc,checksum,(int)Files.size(file)));
        return response;
    }

    private interface Reply {void send(IOSession session, MsgPacket packet) throws Exception;}
    private static class Session extends IOSession implements AutoCloseable {
        private final SocketChannel channel;
        private final Selector selector;
        Session(Reply reply) throws IOException {this(reply,SocketChannel.open(),Selector.open());}
        Session(Reply reply, SocketChannel channel, Selector selector) {
            super(channel,selector,new SocketCodec(new SocketEncode(){
                @Override public void doEncode(IOSession session,MsgPacket packet) throws Exception {
                    assertEquals(ActionType.HTTP_METHOD.name(),packet.getMethodStr()); reply.send(session,packet);
                }
            },new SocketDecode(Runnable::run)),new ClientActionHandler());
            this.channel=channel; this.selector=selector;
        }
        @Override public void close(){super.close();try{channel.close();selector.close();}catch(IOException e){throw new RuntimeException(e);}}
    }
}
