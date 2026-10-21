package com.interviewprep.practice;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The client against a stub runner: answers, busy, down, unconfigured. */
class RunnerClientTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static HttpServer stub(int status, String body) throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/runs", ex -> {
      byte[] b = body.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    s.start();
    return s;
  }

  @Test
  void answersBusyDownAndUnconfigured() throws Exception {
    HttpServer ok = stub(200, "{\"status\":\"finished\",\"cases\":[]}");
    RunnerClient client = new RunnerClient("http://127.0.0.1:" + ok.getAddress().getPort(), JSON);
    assertThat(client.configured()).isTrue();
    RunnerClient.Outcome answered = client.run(JSON.readTree("{}"));
    assertThat(answered).isInstanceOf(RunnerClient.Answered.class);
    assertThat(((RunnerClient.Answered) answered).body().path("status").asString()).isEqualTo("finished");
    ok.stop(0);

    HttpServer busy = stub(503, "{\"status\":\"busy\"}");
    assertThat(new RunnerClient("http://127.0.0.1:" + busy.getAddress().getPort(), JSON).run(JSON.readTree("{}")))
        .isInstanceOf(RunnerClient.Busy.class);
    busy.stop(0);

    // Nothing listens on port 1.
    assertThat(new RunnerClient("http://127.0.0.1:1", JSON).run(JSON.readTree("{}")))
        .isInstanceOf(RunnerClient.Unavailable.class);
    RunnerClient off = new RunnerClient("", JSON);
    assertThat(off.configured()).isFalse();
    assertThat(off.run(JSON.readTree("{}"))).isInstanceOf(RunnerClient.Unavailable.class);
  }

  @Test
  void waitsLongerThanTheRunnersWorstCase() {
    // 20 s queue + 12 s run + up to 10 s each for docker kill and rm + 2 s read: about 54 s.
    assertThat(RunnerClient.ANSWER_TIMEOUT).isEqualTo(java.time.Duration.ofSeconds(60));
  }
}
