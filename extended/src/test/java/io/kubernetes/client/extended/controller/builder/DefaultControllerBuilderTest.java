package io.kubernetes.client.extended.controller.builder;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.*;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.JSON;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.models.*;
import io.kubernetes.client.util.CallGeneratorParams;
import io.kubernetes.client.util.ClientBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DefaultControllerBuilderTest {

  private SharedInformerFactory informerFactory = new SharedInformerFactory();
  private ExecutorService controllerThead = Executors.newSingleThreadExecutor();

  private ApiClient client;

  private static final int PORT = 8089;

  @Rule public WireMockRule wireMockRule = new WireMockRule(PORT);

  private final int stepCooldownIntervalInMillis = 500;

  private void cooldown() {
    try {
      Thread.sleep(stepCooldownIntervalInMillis);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  @Before
  public void setUp() throws Exception {
    client = new ClientBuilder().setBasePath("http://localhost:" + PORT).build();
  }

  @After
  public void tearDown() throws Exception {}

  @Test
  public void testWithLeaderElectorProxiesDefaultController() {}

  @Test(expected = IllegalStateException.class)
  public void testDummyBuildShouldFail() {
    ControllerBuilder.defaultBuilder(informerFactory).build();
  }

  @Test(expected = IllegalStateException.class)
  public void testBuildWatchShouldFailIfInformerAbsent() {
    ControllerBuilder.defaultBuilder(informerFactory).watch(V1ConfigMap.class).endWatch().build();
  }

  @Test
  public void testBuildWatchShouldWorkIfInformerPresent() {
    CoreV1Api api = new CoreV1Api();
    informerFactory.sharedIndexInformerFor(
        (CallGeneratorParams params) -> {
          return api.listPodForAllNamespacesCall(
              null,
              null,
              null,
              null,
              null,
              params.resourceVersion,
              params.timeoutSeconds,
              params.watch,
              null,
              null);
        },
        V1Pod.class,
        V1PodList.class);
    ControllerBuilder.defaultBuilder(informerFactory)
        .watch(V1Pod.class)
        .endWatch()
        .withReconciler(
            new Reconciler() {
              @Override
              public Result reconcile(Request request) {
                return new Result(false);
              }
            })
        .build();
  }

  @Test
  public void testControllerBuilderCustomizationShouldWork() {
    String testName = "test-controller";
    int testWorkerCount = 1024;
    ExecutorService threadPool = Executors.newCachedThreadPool();

    ControllerBuilder.defaultBuilder(informerFactory)
        .withName(testName)
        .withWorkerCount(testWorkerCount)
        .withWorkQueue(null)
        .withReconciler(
            new Reconciler() {
              @Override
              public Result reconcile(Request request) {
                return new Result(false);
              }
            })
        .build();
  }

  @Test
  public void testBuildWatchEventNotificationShouldWork() {
    V1PodList podList =
        new V1PodList()
            .metadata(new V1ListMeta().resourceVersion("0"))
            .items(
                Arrays.asList(
                    new V1Pod()
                        .metadata(new V1ObjectMeta().name("test-pod1"))
                        .spec(new V1PodSpec().hostname("hostname1"))));

    stubFor(
        get(urlPathEqualTo("/api/v1/pods"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(new JSON().serialize(podList))));

    CoreV1Api api = new CoreV1Api(client);
    RateLimitingQueue<Request> workQueue =
        new DefaultRateLimitingQueue<>(Executors.newSingleThreadExecutor());
    SharedIndexInformer<V1Pod> podInformer =
        informerFactory.sharedIndexInformerFor(
            (CallGeneratorParams params) -> {
              return api.listPodForAllNamespacesCall(
                  null,
                  null,
                  null,
                  null,
                  null,
                  params.resourceVersion,
                  params.timeoutSeconds,
                  params.watch,
                  null,
                  null);
            },
            V1Pod.class,
            V1PodList.class);

    List<Request> keyFuncReceivingRequests = new ArrayList<>();
    Function<V1Pod, Request> podKeyFunc =
        (V1Pod pod) -> {
          // twisting pod name key
          Request request =
              new Request(pod.getSpec().getHostname() + "/" + pod.getMetadata().getName());
          keyFuncReceivingRequests.add(request);
          return request;
        };

    List<Request> controllerReceivingRequests = new ArrayList<>();
    Controller testController =
        ControllerBuilder.defaultBuilder(informerFactory)
            .withReconciler(
                new Reconciler() {
                  @Override
                  public Result reconcile(Request request) {
                    controllerReceivingRequests.add(request);
                    return new Result(false);
                  }
                })
            .withWorkQueue(workQueue)
            .watch(V1Pod.class)
            .withWorkQueueKeyFunc(podKeyFunc)
            .endWatch()
            .build();

    controllerThead.submit(testController::run);
    informerFactory.startAllRegisteredInformers();

    Request expectedRequest = new Request("hostname1/test-pod1");

    cooldown();

    assertEquals(1, keyFuncReceivingRequests.size());
    assertEquals(expectedRequest, keyFuncReceivingRequests.get(0));

    assertEquals(1, controllerReceivingRequests.size());
    assertEquals(expectedRequest, controllerReceivingRequests.get(0));
  }
}
