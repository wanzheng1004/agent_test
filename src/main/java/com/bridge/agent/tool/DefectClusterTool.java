package com.bridge.agent.tool;

import com.bridge.agent.entity.DefectRecord;
import com.bridge.agent.util.JsonUtil;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class DefectClusterTool {

    private static final double SIMILARITY_THRESHOLD = 0.85;

    private final EmbeddingModel embeddingModel;
    private final DefectHistoryTool defectHistoryTool;

    public DefectClusterTool(EmbeddingModel embeddingModel,
                             DefectHistoryTool defectHistoryTool) {
        this.embeddingModel = embeddingModel;
        this.defectHistoryTool = defectHistoryTool;
    }

    public String execute(String jsonInput) {
        String bridgeId = JsonUtil.getString(jsonInput, "bridgeId");
        Integer months = JsonUtil.getInt(jsonInput, "months");
        if (months == null) {
            months = 36;
        }

        if (bridgeId == null || bridgeId.isBlank()) {
            return "Error: bridgeId is required";
        }

        List<DefectRecord> records = defectHistoryTool.getRecords(bridgeId, months);
        if (records.isEmpty()) {
            return "Bridge [" + bridgeId + "] has no historical defect records in the last "
                    + months + " months.";
        }

        List<DefectInput> inputs = records.stream()
                .map(record -> new DefectInput(
                        firstNonBlank(record.getDescription(), record.getRawDescription()),
                        record))
                .filter(input -> input.description() != null && !input.description().isBlank())
                .toList();

        if (inputs.isEmpty()) {
            return "Defect records contain no usable description text.";
        }

        return formatClusters(cluster(inputs), bridgeId, months);
    }

    private List<DefectCluster> cluster(List<DefectInput> inputs) {
        List<String> descriptions = inputs.stream().map(DefectInput::description).toList();
        List<float[]> embeddings = embeddingModel.embed(descriptions);
        List<DefectCluster> clusters = new ArrayList<>();

        for (int i = 0; i < inputs.size(); i++) {
            DefectInput input = inputs.get(i);
            float[] embedding = embeddings.get(i);

            boolean merged = false;
            for (DefectCluster cluster : clusters) {
                double similarity = cosineSimilarity(embedding, cluster.centroid);
                if (similarity >= SIMILARITY_THRESHOLD) {
                    cluster.addMember(input.record(), embedding);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                clusters.add(new DefectCluster(input.description(), input.record(), embedding));
            }
        }

        clusters.sort((a, b) -> Integer.compare(b.members.size(), a.members.size()));
        return clusters;
    }

    private String formatClusters(List<DefectCluster> clusters, String bridgeId, int months) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Bridge [%s] high-frequency defect analysis for last %d months, %d clusters:%n%n",
                bridgeId, months, clusters.size()));

        for (int i = 0; i < Math.min(clusters.size(), 5); i++) {
            DefectCluster cluster = clusters.get(i);
            String lastDate = cluster.lastRecord.getInspectionDate() != null
                    ? cluster.lastRecord.getInspectionDate().toString()
                    : "unknown";
            int maxGrade = cluster.members.stream()
                    .map(record -> record.getGrade() != null ? record.getGrade() : 0)
                    .max(Integer::compareTo)
                    .orElse(0);

            sb.append(String.format("TOP%d %s%n", i + 1, cluster.representative));
            sb.append(String.format("  count: %d | latest: %s | max grade: %s%n",
                    cluster.members.size(), lastDate, maxGrade > 0 ? maxGrade : "unknown"));
            if (cluster.members.size() > 1) {
                sb.append("  components: ").append(
                        cluster.members.stream()
                                .map(DefectRecord::getComponent)
                                .filter(Objects::nonNull)
                                .distinct()
                                .collect(Collectors.joining(", "))).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private record DefectInput(String description, DefectRecord record) {
    }

    private static class DefectCluster {
        private final String representative;
        private final float[] centroid;
        private final List<DefectRecord> members = new ArrayList<>();
        private DefectRecord lastRecord;

        DefectCluster(String representative, DefectRecord record, float[] embedding) {
            this.representative = representative;
            this.centroid = embedding.clone();
            this.members.add(record);
            this.lastRecord = record;
        }

        void addMember(DefectRecord record, float[] embedding) {
            members.add(record);
            for (int i = 0; i < centroid.length; i++) {
                centroid[i] = (centroid[i] * (members.size() - 1) + embedding[i]) / members.size();
            }
            if (record.getInspectionDate() != null
                    && (lastRecord.getInspectionDate() == null
                    || record.getInspectionDate().isAfter(lastRecord.getInspectionDate()))) {
                lastRecord = record;
            }
        }
    }
}
