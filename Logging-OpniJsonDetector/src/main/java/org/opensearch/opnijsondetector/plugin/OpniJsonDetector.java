/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.opnipreprocessing.plugin;

import org.opensearch.common.Strings;
import org.opensearch.common.unit.ByteSizeUnit;
import org.opensearch.common.unit.ByteSizeValue;
import org.opensearch.ingest.AbstractProcessor;
import org.opensearch.ingest.IngestDocument;
import org.opensearch.ingest.Processor;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Setting.Property;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.UUID;
import org.opensearch.common.io.PathUtils;


import static org.opensearch.ingest.ConfigurationUtils.readBooleanProperty;
import static org.opensearch.ingest.ConfigurationUtils.readOptionalStringProperty;
import static org.opensearch.ingest.ConfigurationUtils.readStringProperty;

import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedAction;

import org.opensearch.OpenSearchException;
import org.opensearch.ingest.Processor;
import org.opensearch.plugins.IngestPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.common.SuppressForbidden;

import java.nio.file.Files;
import java.nio.file.FileSystem;


import java.io.IOException;
import java.security.GeneralSecurityException;
import java.lang.NullPointerException;



public final class OpniJsonDetector extends AbstractProcessor {

    public static final String TYPE = "opni-json-detector";

    private final String field;
    private final String targetField;

    public OpniJsonDetector(String tag, String description, String field, String targetField)
            throws IOException, PrivilegedActionException {
        super(tag, description);
        this.field = field;
        this.targetField = targetField;
    }

    @Override
    public IngestDocument execute(IngestDocument ingestDocument) throws Exception {
        // main entry
        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<IngestDocument>() {
                @Override
                public IngestDocument run() throws Exception {
                    String generated_id = getRandomID();
                    ingestDocument.setFieldValue("_id", generated_id);
                    preprocessingDocument(ingestDocument);
                    //publishToNats(ingestDocument, nc);
                    if (!ingestDocument.getFieldValue("log_type", String.class).equals("workload")) {
                        publishToNats(ingestDocument, nc);
                    }
                    return ingestDocument;
                }
            });
        } catch (PrivilegedActionException e) {
            throw e;
        }         
    }

    public String getRandomID() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String getType() {
        return TYPE;
    }


    // @SuppressWarnings({"unchecked"})
    @SuppressForbidden(reason = "only use PathUtil to get filename")
    private void preprocessingDocument(IngestDocument ingestDocument) {
        /**
        preprocessing documents:
        0. initialize a few fields for downstream AI services.
        1. normalize time field
        2. normalize log field
        3. identify controlplane/rancher logs
        **/
        ingestDocument.setFieldValue("template_matched", "");
        ingestDocument.setFieldValue("anomaly_level", "");
        long unixTime = System.currentTimeMillis();
        ingestDocument.setFieldValue("ingest_at", ((Date)new Timestamp(unixTime)).toString());

        // normalize field `time`
        if (!ingestDocument.hasField("time")){
            if (ingestDocument.hasField("timestamp")) {
                ingestDocument.setFieldValue("time", ingestDocument.getFieldValue("timestamp", String.class));
                ingestDocument.setFieldValue("raw_ts", "yes");
            }
            else {
                ingestDocument.setFieldValue("time", Long.toString(unixTime));
                ingestDocument.setFieldValue("raw_ts", "no");
            }
        }
        else {
            ingestDocument.setFieldValue("raw_ts", "raw time");
        }
        if (ingestDocument.hasField("timestamp")) {
            ingestDocument.removeField("timestamp"); 
        }

        // If it's an event we don't need to do any further processing
        if (ingestDocument.hasField("log_type") && ingestDocument.getFieldValue("log_type", String.class).equals("event")) {
            return;
        }

        // normalize field `log`
        String actualLog = "NONE";
        if (!ingestDocument.hasField("log")) {
            if (ingestDocument.hasField("message")) {
                actualLog = ingestDocument.getFieldValue("message", String.class);              
                ingestDocument.removeField("message");
                ingestDocument.setFieldValue("log_source_field", "message");
            }
            else if (ingestDocument.hasField("MESSAGE")) {
                actualLog = ingestDocument.getFieldValue("MESSAGE", String.class);
                ingestDocument.removeField("MESSAGE");
                ingestDocument.setFieldValue("log_source_field", "MESSAGE");
            }
            else {
                ingestDocument.setFieldValue("log_source_field", "NONE");
            }
        }
        else {
            actualLog = ingestDocument.getFieldValue("log", String.class);
            ingestDocument.setFieldValue("log_source_field", "log");
        }
        actualLog = actualLog.trim();
        ingestDocument.setFieldValue("log", actualLog);

        // Don't do any further processing if the logs come from the support agent
        if (ingestDocument.hasField("agent") && ingestDocument.getFieldValue("agent", String.class).equals("support")) {
            return;
        }

        // normalize field log_type and kubernetesComponent conponent
        String logType = "workload";
        String kubernetesComponent = "";
        
        if (ingestDocument.hasField("filename")) {
            String controlPlaneName = ingestDocument.getFieldValue("filename", String.class);
            if (controlPlaneName.contains("rke/log/etcd") ||
                controlPlaneName.contains("rke/log/kubelet") ||
                controlPlaneName.contains("/rke/log/kube-apiserver") ||
                controlPlaneName.contains("rke/log/kube-controller-manager") ||
                controlPlaneName.contains("rke/log/kube-proxy") ||
                controlPlaneName.contains("rke/log/kube-scheduler") 
                ) { 
                logType = "controlplane";
                controlPlaneName = PathUtils.get(controlPlaneName).getFileName().toString();
                kubernetesComponent = (controlPlaneName.split("_"))[0];
            }
            else if (controlPlaneName.contains("k3s.log")){
                logType = "controlplane";
                kubernetesComponent = "k3s";
            }
            else if (controlPlaneName.contains("rke2/agent/logs/kubelet")){
                logType = "controlplane";
                kubernetesComponent = "kubelet";
            }
        }  
        else if (ingestDocument.hasField("COMM")){
            String controlPlaneName = ingestDocument.getFieldValue("COMM", String.class);
            if (controlPlaneName.contains("kubelet") ||
                controlPlaneName.contains("k3s-agent") ||
                controlPlaneName.contains("k3s-server") ||
                controlPlaneName.contains("rke2-agent") ||
                controlPlaneName.contains("rke2-server")
                ){
                logType = "controlplane";
                kubernetesComponent = (controlPlaneName.split("-"))[0];
            }
        }
        else {
            if (ingestDocument.hasField("kubernetes")) {// kubernetes.labels.tier
                Map<String, Object> kubernetes = ingestDocument.getFieldValue("kubernetes", Map.class);
                if (kubernetes.containsKey("labels")) {
                    HashMap<String, String> labels = (HashMap)kubernetes.get("labels");
                    if (labels.containsKey("tier")) {
                        String controlPlaneName = labels.get("tier");
                        if (controlPlaneName.contains("control-plane")){
                            logType = "controlplane";
                            kubernetesComponent = "control-plane";
                        }
                    }
                }
                if (kubernetes.containsKey("container_image") && ((String)kubernetes.get("container_image")).contains("rancher/rancher") &&
                    ingestDocument.hasField("deployment") && ingestDocument.getFieldValue("deployment", String.class).equals("rancher")  ) {
                    logType = "rancher";
                }
                if (kubernetes.containsKey("container_image") && ((String)kubernetes.get("container_image")).contains("longhornio-")) {
                    logType = "longhorn";
                }
                ingestDocument.setFieldValue("pod_name", ((String)kubernetes.get("pod_name")));
            }        
        }  
        ingestDocument.setFieldValue("log_type", logType);
        ingestDocument.setFieldValue("kubernetes_component", kubernetesComponent);
    }

    public static final class Factory implements Processor.Factory {

        public Factory() {

        }

        @Override
        public Processor create(Map<String, Processor.Factory> processorFactories, String tag, String description,
                                Map<String, Object> config) throws Exception {
            String field = readStringProperty(TYPE, tag, config, "field");
            String targetField = readStringProperty(TYPE, tag, config, "target_field");

            return new OpniJsonDetector(tag, description, field, targetField);
        }
    }
}