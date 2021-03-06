/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.arquillian.ce.fabric8;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.dsl.ClientNonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.ClientPodResource;
import io.fabric8.kubernetes.client.dsl.ClientResource;
import io.fabric8.kubernetes.client.dsl.Deletable;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.api.model.DeploymentConfigStatus;
import io.fabric8.openshift.api.model.DoneableDeploymentConfig;
import io.fabric8.openshift.api.model.DoneableTemplate;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.api.model.RoleBinding;
import io.fabric8.openshift.api.model.RoleBindingBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.openshift.api.model.WebHookTriggerBuilder;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.NamespacedOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import io.fabric8.openshift.client.ParameterValue;
import io.fabric8.openshift.client.dsl.ClientDeployableScalableResource;
import io.fabric8.openshift.client.dsl.ClientTemplateResource;
import okhttp3.Response;

import org.apache.commons.io.IOUtils;
import org.jboss.arquillian.ce.adapter.AbstractOpenShiftAdapter;
import org.jboss.arquillian.ce.api.MountSecret;
import org.jboss.arquillian.ce.api.model.OpenShiftResource;
import org.jboss.arquillian.ce.fabric8.model.F8DeploymentConfig;
import org.jboss.arquillian.ce.portfwd.PortForwardContext;
import org.jboss.arquillian.ce.proxy.Proxy;
import org.jboss.arquillian.ce.resources.OpenShiftResourceHandle;
import org.jboss.arquillian.ce.utils.Checker;
import org.jboss.arquillian.ce.utils.Configuration;
import org.jboss.arquillian.ce.utils.Containers;
import org.jboss.arquillian.ce.utils.HookType;
import org.jboss.arquillian.ce.utils.Operator;
import org.jboss.arquillian.ce.utils.ParamValue;
import org.jboss.arquillian.ce.utils.Port;
import org.jboss.arquillian.ce.utils.RCContext;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.dmr.ModelNode;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class F8OpenShiftAdapter extends AbstractOpenShiftAdapter {
    private static final String BOUND = "Bound";

    private final NamespacedOpenShiftClient client;
    private Map<String, KubernetesList> templates = new ConcurrentHashMap<>();

    static OpenShiftConfig toOpenShiftConfig(Configuration configuration) {
        OpenShiftConfigBuilder builder = new OpenShiftConfigBuilder()
            .withMasterUrl(configuration.getKubernetesMaster())
            .withTrustCerts(configuration.isTrustCerts());

        if (configuration.hasOpenshiftBasicAuth()) {
            builder
                .withUsername(configuration.getOpenshiftUsername())
                .withPassword(configuration.getOpenshiftPassword());
        }

        return builder.build();
    }

    static NamespacedOpenShiftClient create(Configuration configuration) {
        OpenShiftConfig config = toOpenShiftConfig(configuration);
        return new DefaultOpenShiftClient(config);
    }

    public F8OpenShiftAdapter(Configuration configuration) {
        super(configuration);
        this.client = create(configuration);
    }

    public F8OpenShiftAdapter(NamespacedOpenShiftClient client, Configuration configuration) {
        super(configuration);
        this.client = client;
    }

    public String exec(Map<String, String> labels, int waitSeconds, String... input) throws Exception {
        List<Pod> pods = client.inAnyNamespace().pods().withLabels(labels).list().getItems();
        if (pods.isEmpty()) {
            throw new IllegalStateException("No such pod: " + labels);
        }
        Pod targetPod = pods.get(0);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        client.inNamespace(targetPod.getMetadata().getNamespace()).pods().withName(targetPod.getMetadata().getName())
            .readingInput(System.in)
            .writingOutput(output)
            .writingError(System.err)
            .withTTY()
            .usingListener(new SimpleListener())
            .exec(input);

        Thread.sleep(waitSeconds * 1000);

        output.flush();
        return output.toString();
    }

    private static class SimpleListener implements ExecListener {
        public void onOpen(Response response) {
            System.out.println("Exec open");
        }

        public void onFailure(IOException e, Response response) {
            System.err.println("Exec failure");
            e.printStackTrace();
        }

        public void onClose(int code, String reason) {
            System.out.println("Exec close");
        }
    }

    protected Proxy createProxy() {
        return new F8Proxy(configuration, client);
    }

    @Override
    public PortForwardContext createPortForwardContext(Map<String, String> labels, int port) {
        List<Pod> pods = client.pods().inNamespace(configuration.getNamespace()).withLabels(labels).list().getItems();
        if (pods.isEmpty()) {
            throw new IllegalStateException("No such pods: " + labels);
        }
        Pod pod = pods.get(0);
        String nodeName = pod.getStatus().getHostIP();
        return new PortForwardContext(configuration.getKubernetesMaster(), nodeName, configuration.getNamespace(), pod.getMetadata().getName(), port);
    }

    public RegistryLookupEntry lookup() {
        // Grab Docker registry service
        Service service = getService(configuration.getRegistryNamespace(), configuration.getRegistryServiceName());
        ServiceSpec spec = service.getSpec();
        String ip = spec.getClusterIP();
        if (ip == null) {
            ip = spec.getPortalIP();
        }
        Integer port = findHttpServicePort(spec.getPorts());
        return new RegistryLookupEntry(ip, String.valueOf(port));
    }

    private Object createProject() {
        // oc new-project <namespace>
        return client.projectrequests().createNew().withNewMetadata().withName(configuration.getNamespace()).endMetadata().done();
    }

    public boolean checkProject() {
        for (Project project : client.projects().list().getItems()) {
            if (configuration.getNamespace().equals(KubernetesHelper.getName(project))) {
                return false;
            }
        }
        return createProject() != null;
    }

    public boolean deleteProject() {
        return client.projects().withName(configuration.getNamespace()).delete();
    }

    public void deletePod(String podName, long gracePeriodSeconds) {
        ClientPodResource<Pod, DoneablePod> resource = client.pods().inNamespace(configuration.getNamespace()).withName(podName);
        Deletable<Boolean> deletable = resource;
        if (gracePeriodSeconds >= 0) {
            deletable = resource.withGracePeriod(gracePeriodSeconds);
        }
        deletable.delete();
    }

    public void triggerDeploymentConfigUpdate(String prefix, boolean wait) throws Exception {
        DeploymentConfigList list = client.deploymentConfigs().inNamespace(configuration.getNamespace()).list();
        String actualName = getActualName(prefix, list.getItems(), "No such deployment config: " + prefix);
        final ClientResource<DeploymentConfig, DoneableDeploymentConfig> ccr = client.deploymentConfigs().inNamespace(configuration.getNamespace()).withName(actualName);
        List<Container> containers = ccr.get().getSpec().getTemplate().getSpec().getContainers();
        if (containers.size() > 0) {
            // there should be one to do upgrade
            Container container = containers.get(0);
            List<EnvVar> oldEnv = container.getEnv();
            List<EnvVar> newEnv = new ArrayList<>(oldEnv);
            newEnv.add(new EnvVar("_DUMMY", "_VALUE", null));
            container.setEnv(newEnv);
            ccr.edit().editSpec().editTemplate().editSpec().withContainers(containers).endSpec().endTemplate().endSpec().done();
        }
        if (wait) {
            final int replicas = ccr.get().getSpec().getReplicas();
            Containers.delay(configuration.getStartupTimeout(), 3000L, new Checker() {
                public boolean check() {
                    DeploymentConfigStatus status = ccr.get().getStatus();
                    Map<String, Object> additionalProperties = status.getAdditionalProperties();
                    Number updatedReplicas = (Number) additionalProperties.get("updatedReplicas");
                    if (updatedReplicas != null && replicas == updatedReplicas.intValue()) {
                        Number availableReplicas = (Number) additionalProperties.get("availableReplicas");
                        return (availableReplicas != null && replicas == availableReplicas.intValue());
                    } else {
                        return false;
                    }
                }
            });
        }
    }

    public String deployPod(String name, String env, RCContext context) throws Exception {
        List<Container> containers = getContainers(name, context);

        PodSpec podSpec = new PodSpec();
        podSpec.setContainers(containers);

        Map<String, String> podLabels = new HashMap<>();
        podLabels.put("name", name + "-pod");
        podLabels.putAll(context.getLabels());

        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name + "-pod");
        metadata.setLabels(podLabels);

        mountSecret(podSpec, context.getMountSecret());

        Pod pod = new Pod();
        pod.setApiVersion(configuration.getApiVersion());
        pod.setMetadata(metadata);
        pod.setSpec(podSpec);

        return client.pods().inNamespace(configuration.getNamespace()).create(pod).getMetadata().getName();
    }

    public String deployReplicationController(String name, String env, RCContext context) throws Exception {
        List<Container> containers = getContainers(name, context);

        Map<String, String> podLabels = new HashMap<>();
        podLabels.put("name", name + "-pod");
        podLabels.putAll(context.getLabels());
        PodTemplateSpec podTemplate = createPodTemplateSpec(podLabels, containers, context.getMountSecret());

        Map<String, String> selector = Collections.singletonMap("name", name + "-pod");
        Map<String, String> labels = Collections.singletonMap("name", name + "Controller");
        ReplicationController rc = createReplicationController(name + "rc", configuration.getApiVersion(), labels, context.getReplicas(), selector, podTemplate);

        return client.replicationControllers().inNamespace(configuration.getNamespace()).create(rc).getMetadata().getName();
    }

    private List<Container> getContainers(String name, RCContext context) throws Exception {
        List<EnvVar> envVars = Collections.emptyList();

        List<ContainerPort> cps = new ArrayList<>();
        for (Port port : context.getPorts()) {
            ContainerPort cp = new ContainerPort();
            cp.setName(port.getName());
            cp.setContainerPort(port.getContainerPort());
            cps.add(cp);
        }

        List<VolumeMount> volumeMounts;
        MountSecret mountSecret = context.getMountSecret();
        if (mountSecret != null) {
            VolumeMount volumeMount = new VolumeMount();
            volumeMount.setName(mountSecret.volumeName());
            volumeMount.setMountPath(mountSecret.mountPath());
            volumeMounts = Collections.singletonList(volumeMount);
        } else {
            volumeMounts = Collections.emptyList();
        }

        Lifecycle lifecycle = null;
        if (!context.isIgnorePreStop() && context.getLifecycleHook() != null && context.getPreStopPath() != null) {
            lifecycle = new Lifecycle();
            Handler preStopHandler = createHandler(context.getLifecycleHook(), context.getPreStopPath(), cps);
            lifecycle.setPreStop(preStopHandler);
        }

        Probe probe = null;
        if (context.getProbeCommands() != null && context.getProbeCommands().size() > 0 && context.getProbeHook() != null) {
            probe = new Probe();
            handleProbe(probe, context.getProbeHook(), context.getProbeCommands(), cps);
        }

        Container container = createContainer(context.getImageName(), name + "-container", envVars, cps, volumeMounts, lifecycle, probe, configuration.getImagePullPolicy());

        return Collections.singletonList(container);
    }

    private Handler createHandler(HookType hookType, String preStopPath, List<ContainerPort> ports) {
        Handler preStopHandler = new Handler();
        switch (hookType) {
            case HTTP_GET:
                HTTPGetAction httpGet = new HTTPGetAction();
                httpGet.setPath(preStopPath);
                httpGet.setPort(findHttpContainerPort(ports));
                preStopHandler.setHttpGet(httpGet);
                break;
            case EXEC:
                ExecAction exec = new ExecAction(Collections.singletonList(preStopPath));
                preStopHandler.setExec(exec);
                break;
            default:
                throw new IllegalArgumentException("Unsupported hook type: " + hookType);
        }
        return preStopHandler;
    }

    private void handleProbe(Probe probe, HookType hookType, List<String> probeCommands, List<ContainerPort> ports) {
        switch (hookType) {
            case HTTP_GET:
                HTTPGetAction httpGet = new HTTPGetAction();
                httpGet.setPath(probeCommands.get(0));
                httpGet.setPort(findHttpContainerPort(ports));
                probe.setHttpGet(httpGet);
                break;
            case EXEC:
                ExecAction exec = new ExecAction(probeCommands);
                probe.setExec(exec);
                break;
            default:
                throw new IllegalArgumentException("Unsupported hook type: " + hookType);
        }
    }

    public List<? extends OpenShiftResource> processTemplateAndCreateResources(String templateKey, String templateURL, List<ParamValue> values, Map<String, String> labels) throws Exception {
        List<ParameterValue> pvs = new ArrayList<>();
        for (ParamValue value : values) {
            pvs.add(new ParameterValue(value.getName(), value.getValue()));
        }
        KubernetesList list = processTemplate(templateURL, pvs, labels);
        KubernetesList result = createResources(list);
        templates.put(templateKey, result);

        List<PersistentVolumeClaim> claims = new ArrayList<>();
        List<DeploymentConfig> configs = new ArrayList<>();
        for (HasMetadata item : result.getItems()) {
            if (item instanceof PersistentVolumeClaim) {
                claims.add((PersistentVolumeClaim) item);
            } else if (item instanceof DeploymentConfig) {
                configs.add((DeploymentConfig) item);
            }
        }

        List<OpenShiftResource> retVal = new ArrayList<>();
        for (DeploymentConfig dc : configs) {
            verifyServiceAccounts(dc);
            retVal.add(new F8DeploymentConfig(dc));
        }
        return retVal;
    }

    private void verifyServiceAccounts(DeploymentConfig dc) throws Exception {
        String serviceAccountName = dc.getSpec().getTemplate().getSpec().getServiceAccountName();
        if (serviceAccountName != null) {
            ServiceAccount serviceAccount = client.serviceAccounts().inNamespace(configuration.getNamespace()).withName(serviceAccountName).get();
            if (serviceAccount == null) {
                throw new Exception("Missing required ServiceAccount: " + serviceAccountName);
            }
        }
    }

    private KubernetesList processTemplate(String templateURL, List<ParameterValue> values, Map<String, String> labels) throws IOException {
        try (InputStream stream = new URL(templateURL).openStream()) {
            ClientTemplateResource<Template, KubernetesList, DoneableTemplate> templateHandle = client.templates().inNamespace(configuration.getNamespace()).load(stream);
            Template template = templateHandle.get();
            if (template.getLabels() == null) {
                template.setLabels(new HashMap<String, String>());
            }
            template.getLabels().putAll(labels);
            return templateHandle.process(values.toArray(new ParameterValue[values.size()]));
        }
    }

    private KubernetesList createResources(KubernetesList list) {
        return client.lists().inNamespace(configuration.getNamespace()).create(list);
    }

    private Object triggerBuild(String namespace, String buildName, String secret, String type) throws Exception {
        return client.buildConfigs().inNamespace(namespace).withName(buildName).withSecret(secret).withType(type).trigger(new WebHookTriggerBuilder().withSecret(secret).build());
    }

    protected OpenShiftResourceHandle createResourceFromStream(InputStream stream) throws IOException {
    	
    	try {
    		String content = IOUtils.toString(stream, StandardCharsets.UTF_8);
	        
    		try {
	        	ModelNode json;
	            json = ModelNode.fromJSONString(content);
	            String kind = json.get("kind").asString();
	            
	            content = json.toJSONString(true);
	            return createResourceFromString(kind, content);
	        } catch (IllegalArgumentException e) {
	        	StringTokenizer tokenizer = new StringTokenizer(content.trim(),":\n");
	        	tokenizer.nextToken();
	        	String kind = tokenizer.nextToken().trim();
	        	
	        	return createResourceFromString(kind, content);
	        }
	        
        } finally {
            stream.close();
        }
    }
    
    private OpenShiftResourceHandle createResourceFromString(String kind, String content) {
    	if ("List".equalsIgnoreCase(kind)) {
            return new ListOpenShiftResourceHandle(content);
        } else if ("Secret".equalsIgnoreCase(kind)) {
            return new SecretOpenShiftResourceHandle(content);
        } else if ("ImageStream".equalsIgnoreCase(kind)) {
            return new ImageStreamOpenShiftResourceHandle(content);
        } else if ("ServiceAccount".equalsIgnoreCase(kind)) {
            return new ServiceAccountOpenShiftResourceHandle(content);
        } else if ("Route".equalsIgnoreCase(kind)) {
            return new RouteOpenShiftResourceHandle(content);
        } else {
            throw new IllegalArgumentException(String.format("Kind '%s' not yet supported -- use Native OpenShift adapter!", kind));
        }
    }

    public Object deleteTemplate(String templateKey) throws Exception {
        KubernetesList config = templates.get(templateKey);
        if (config != null) {
            return client.lists().inNamespace(configuration.getNamespace()).delete(config);
        }
        return config;
    }

    protected OpenShiftResourceHandle createRoleBinding(String roleRefName, String userName) {
        String subjectName = userName.substring(userName.lastIndexOf(":") + 1);
        final RoleBinding roleBinding = client
            .roleBindings()
            .inNamespace(configuration.getNamespace())
            .create(
                new RoleBindingBuilder()
                .withNewMetadata().withName(roleRefName + "-" + subjectName).endMetadata()
                .withNewRoleRef().withName(roleRefName).endRoleRef()
                .addToUserNames(userName)
                .addNewSubject().withKind("ServiceAccount").withNamespace(configuration.getNamespace()).withName(subjectName).endSubject()
                .build()
            );
        return new OpenShiftResourceHandle() {
            public void delete() {
                client.roleBindings().inNamespace(configuration.getNamespace()).delete(roleBinding);
            }
        };
    }

    private String deployService(String name, String apiVersion, String portName, int port, int containerPort, Map<String, String> selector) throws Exception {
        Service service = new Service();

        service.setApiVersion(apiVersion);

        ObjectMeta objectMeta = new ObjectMeta();
        service.setMetadata(objectMeta);
        objectMeta.setName(name);

        ServiceSpec spec = new ServiceSpec();
        service.setSpec(spec);

        ServicePort sp = new ServicePort();
        sp.setName(portName);
        sp.setPort(port);
        sp.setTargetPort(new IntOrString(containerPort));
        spec.setPorts(Collections.singletonList(sp));

        spec.setSelector(selector);

        return client.services().inNamespace(configuration.getNamespace()).create(service).getMetadata().getName();
    }

    public Service getService(String namespace, String serviceName) {
        return client.services().inNamespace(namespace).withName(serviceName).get();
    }

    private ClientDeployableScalableResource<DeploymentConfig, DoneableDeploymentConfig> getDC(String prefix) throws Exception {
        DeploymentConfigList list = client.deploymentConfigs().inNamespace(configuration.getNamespace()).list();
        String actualName = getActualName(prefix, list.getItems(), "No DC found starting with " + prefix);
        return client.deploymentConfigs().inNamespace(configuration.getNamespace()).withName(actualName);
    }

    private void delayDeployment(DeploymentConfig dc, String prefix, int replicas, Operator op) throws Exception {
        final Map<String, String> labels = dc.getSpec().getSelector();
        try {
            delay(labels, replicas, op);
        } catch (Exception e) {
            throw new DeploymentException(String.format("Timeout waiting for deployment %s to scale to %s pods", prefix, replicas), e);
        }
    }

    protected Map<String, String> getLabels(String prefix) throws Exception {
        return getDC(prefix).get().getSpec().getSelector();
    }

    public void scaleDeployment(final String prefix, final int replicas) throws Exception {
        DeploymentConfig dc = getDC(prefix).scale(replicas);
        delayDeployment(dc, prefix, replicas, Operator.EQUAL);
    }

    public List<String> getPods(String prefix) throws Exception {
        PodList pods;
        if (prefix == null) {
            pods = client.pods().inNamespace(configuration.getNamespace()).list();
        } else {
            pods = client.pods().inNamespace(configuration.getNamespace()).withLabels(getLabels(prefix)).list();
        }
        List<String> podNames = new ArrayList<>();
        for (Pod pod : pods.getItems()) {
            podNames.add(pod.getMetadata().getName());
        }
        return podNames;
    }

    public String getLog(String podName) throws Exception {
        log.info("Retrieving logs from pod " + podName);
        return client.pods().inNamespace(configuration.getNamespace()).withName(podName).getLog();
    }

    public InputStream streamLog(String podName) throws Exception {
        return client.pods().inNamespace(configuration.getNamespace()).withName(podName).watchLog().getOutput();
    }

    public String getLog(String prefix, Map<String, String> labels) throws Exception {
        List<Pod> pods;
        ClientNonNamespaceOperation<Pod, PodList, DoneablePod, ClientPodResource<Pod, DoneablePod>> allPods = client.pods().inNamespace(configuration.getNamespace());

        if (labels == null) {
            pods = allPods.list().getItems();
        } else {
            pods = allPods.withLabels(labels).list().getItems();
        }

        String actualName;

        if (prefix != null) {
            actualName = getActualName(prefix, pods, String.format("No pod found starting with '%s' and labels %s.", prefix, labels));
        } else {
            if (pods.isEmpty()) {
                throw new Exception("No pod found with labels " + labels);
            }
            actualName = pods.get(0).getMetadata().getName();
        }
        return getLog(actualName);
    }

    private String getActualName(String prefix, Iterable<? extends HasMetadata> objects, String msg) throws Exception {
        for (HasMetadata hmd : objects) {
            String name = hmd.getMetadata().getName();
            if (name.startsWith(prefix)) {
                return name;
            }
        }
        throw new Exception(msg);
    }

    private Container createContainer(String image, String name, List<EnvVar> envVars, List<ContainerPort> ports, List<VolumeMount> volumes, Lifecycle lifecycle, Probe probe, String imagePullPolicy) throws Exception {
        Container container = new Container();
        container.setImage(image);
        container.setName(name);
        container.setEnv(envVars);
        container.setPorts(ports);
        container.setVolumeMounts(volumes);
        container.setLifecycle(lifecycle);
        container.setReadinessProbe(probe);
        container.setImagePullPolicy(imagePullPolicy);
        return container;
    }

    private PodTemplateSpec createPodTemplateSpec(Map<String, String> labels, List<Container> containers, MountSecret mountSecret) throws Exception {
        PodTemplateSpec pts = new PodTemplateSpec();

        ObjectMeta objectMeta = new ObjectMeta();
        pts.setMetadata(objectMeta);
        objectMeta.setLabels(labels);

        PodSpec ps = new PodSpec();
        pts.setSpec(ps);
        ps.setContainers(containers);

        mountSecret(ps, mountSecret);

        return pts;
    }

    private static void mountSecret(PodSpec ps, MountSecret mountSecret) {
        if (mountSecret != null) {
            Volume volume = new Volume();
            volume.setName(mountSecret.volumeName());

            SecretVolumeSource svc = new SecretVolumeSource();
            svc.setSecretName(mountSecret.secretName());
            volume.setSecret(svc);

            ps.setVolumes(Collections.singletonList(volume));
        }
    }

    private ReplicationController createReplicationController(String name, String apiVersion, Map<String, String> labels, int replicas, Map<String, String> selector, PodTemplateSpec podTemplate) throws Exception {
        ReplicationController rc = new ReplicationController();

        rc.setApiVersion(apiVersion);

        ObjectMeta objectMeta = new ObjectMeta();
        rc.setMetadata(objectMeta);
        objectMeta.setName(name);
        objectMeta.setLabels(labels);

        ReplicationControllerSpec spec = new ReplicationControllerSpec();
        rc.setSpec(spec);
        spec.setReplicas(replicas);
        spec.setSelector(selector);
        spec.setTemplate(podTemplate);

        return rc;
    }

    public void cleanServices(String... ids) throws Exception {
        for (String id : ids) {
            try {
                boolean exists = client.services().inNamespace(configuration.getNamespace()).withName(id).cascading(false).delete();
                log.info(String.format("Service [%s] delete: %s.", id, exists));
            } catch (Exception e) {
                log.log(Level.WARNING, String.format("Exception while deleting service [%s]: %s", id, e), e);
            }
        }
    }

    public void cleanReplicationControllers(String... ids) throws Exception {
        for (String id : ids) {
            try {
                boolean exists = client.replicationControllers().inNamespace(configuration.getNamespace()).withName(id).cascading(false).delete();
                log.info(String.format("RC [%s] delete: %s.", id, exists));
            } catch (Exception e) {
                log.log(Level.WARNING, String.format("Exception while deleting RC [%s]: %s", id, e), e);
            }
        }
    }

    public void cleanPods(Map<String, String> labels) throws Exception {
        final PodList pods = client.pods().inNamespace(configuration.getNamespace()).withLabels(labels).list();
        try {
            for (Pod pod : pods.getItems()) {
                String podId = KubernetesHelper.getName(pod);
                boolean exists = client.pods().inNamespace(configuration.getNamespace()).withName(podId).delete();
                log.info(String.format("Pod [%s] delete: %s.", podId, exists));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, String.format("Exception while deleting pod [%s]: %s", labels, e), e);
        }
    }

    @Override
    public void cleanRemnants(Map<String, String> labels) throws Exception {
        cleanBuilds(labels);
        cleanDeployments(labels);
    }

    private void cleanBuilds(Map<String, String> labels) throws Exception {
        final BuildList builds = client.builds().inNamespace(configuration.getNamespace()).withLabels(labels).list();
        try {
            for (Build build : builds.getItems()) {
                String buildId = KubernetesHelper.getName(build);
                boolean exists = client.builds().inNamespace(configuration.getNamespace()).withName(buildId).delete();
                log.info(String.format("Build [%s] delete: %s.", buildId, exists));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, String.format("Exception while deleting build [%s]: %s", labels, e), e);
        }
    }

    private void cleanDeployments(Map<String, String> labels) throws Exception {
        final ReplicationControllerList rcs = client.replicationControllers().inNamespace(configuration.getNamespace()).withLabels(labels).list();
        try {
            for (ReplicationController rc : rcs.getItems()) {
                String rcId = KubernetesHelper.getName(rc);
                client.replicationControllers().inNamespace(configuration.getNamespace()).withName(rcId).scale(0, true);
                boolean exists = client.replicationControllers().inNamespace(configuration.getNamespace()).withName(rcId).delete();
                log.info(String.format("ReplicationController [%s] delete: %s.", rcId, exists));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, String.format("Exception while deleting rc [%s]: %s", labels, e), e);
        }
    }

    public void close() throws IOException {
        templates.clear();
        if (client != null) {
            client.close();
        }
    }

    static IntOrString toIntOrString(ContainerPort port) {
        IntOrString intOrString = new IntOrString();
        intOrString.setIntVal(port.getContainerPort());
        return intOrString;
    }

    static Integer findHttpServicePort(List<ServicePort> ports) {
        return findServicePort(ports, "http");
    }

    static Integer findServicePort(List<ServicePort> ports, String name) {
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("Empty ports!");
        }
        if (ports.size() == 1) {
            return ports.get(0).getPort();
        }
        for (ServicePort port : ports) {
            if (name.equals(port.getName())) {
                return port.getPort();
            }
        }
        throw new IllegalArgumentException("No such port: " + name);
    }

    static IntOrString findHttpContainerPort(List<ContainerPort> ports) {
        return findContainerPort(ports, "http");
    }

    static IntOrString findContainerPort(List<ContainerPort> ports, String name) {
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("Empty ports!");
        }
        if (ports.size() == 1) {
            return toIntOrString(ports.get(0));
        }
        for (ContainerPort port : ports) {
            if (name.equals(port.getName())) {
                return toIntOrString(port);
            }
        }
        throw new IllegalArgumentException("No such port: " + name);
    }

    private abstract class AbstractOpenShiftResourceHandle<T> implements OpenShiftResourceHandle {
        protected final T resource;

        public AbstractOpenShiftResourceHandle(String content) {
            resource = createResource(new ByteArrayInputStream(content.getBytes()));
        }

        protected abstract T createResource(InputStream stream);
    }

    private class ListOpenShiftResourceHandle extends AbstractOpenShiftResourceHandle<KubernetesList> {
        public ListOpenShiftResourceHandle(String content) {
            super(content);
        }

        protected KubernetesList createResource(InputStream stream) {
            return client.lists().inNamespace(configuration.getNamespace()).load(stream).create();
        }

        public void delete() {
            client.lists().inNamespace(configuration.getNamespace()).delete(resource);
        }
    }

    private class SecretOpenShiftResourceHandle extends AbstractOpenShiftResourceHandle<Secret> {
        public SecretOpenShiftResourceHandle(String content) {
            super(content);
        }

        protected Secret createResource(InputStream stream) {
            return client.secrets().inNamespace(configuration.getNamespace()).load(stream).create();
        }

        public void delete() {
            client.secrets().inNamespace(configuration.getNamespace()).delete(resource);
        }
    }

    private class ImageStreamOpenShiftResourceHandle extends AbstractOpenShiftResourceHandle<ImageStream> {
        public ImageStreamOpenShiftResourceHandle(String content) {
            super(content);
        }

        protected ImageStream createResource(InputStream stream) {
            return client.imageStreams().inNamespace(configuration.getNamespace()).load(stream).create();
        }

        public void delete() {
            client.imageStreams().inNamespace(configuration.getNamespace()).delete(resource);
        }
    }

    private class ServiceAccountOpenShiftResourceHandle extends AbstractOpenShiftResourceHandle<ServiceAccount> {
        public ServiceAccountOpenShiftResourceHandle(String content) {
            super(content);
        }

        protected ServiceAccount createResource(InputStream stream) {
            return client.serviceAccounts().inNamespace(configuration.getNamespace()).load(stream).create();
        }

        public void delete() {
            client.serviceAccounts().inNamespace(configuration.getNamespace()).delete(resource);
        }
    }

    private class RouteOpenShiftResourceHandle extends AbstractOpenShiftResourceHandle<Route> {
        public RouteOpenShiftResourceHandle(String content) {
            super(content);
        }

        protected Route createResource(InputStream stream) {
            return client.routes().inNamespace(configuration.getNamespace()).load(stream).create();
        }

        public void delete() {
            client.routes().inNamespace(configuration.getNamespace()).delete(resource);
        }
    }
}
