import React from "react";
import { FlatList, Pressable, StyleSheet, Text, View } from "react-native";
import type { DownloadJob } from "../domain/types.ts";
import type { DownloadManager } from "../bridge/downloadManager.ts";
import { useDownloadJobs } from "./useDownloadJobs.tsx";

interface DownloadScreenProps {
  manager: DownloadManager;
  onStart: () => Promise<void>;
}

function formatProgress(job: DownloadJob): string {
  const total = job.progress.totalBytes ?? 0;
  const totalText = total > 0 ? ` / ${total}` : "";
  return `${job.progress.bytesDownloaded}${totalText}`;
}

export function DownloadScreen(props: DownloadScreenProps): JSX.Element {
  const { jobs, lastError, pause, resume, cancel } = useDownloadJobs(props.manager);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Downloads</Text>
      {lastError ? <Text style={styles.error}>{lastError.message}</Text> : null}
      <Pressable style={styles.startButton} onPress={props.onStart}>
        <Text style={styles.startButtonText}>Start Download</Text>
      </Pressable>
      <FlatList
        data={jobs}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => (
          <View style={styles.card}>
            <Text style={styles.jobTitle}>{item.id}</Text>
            <Text style={styles.jobMeta}>{item.state}</Text>
            <Text style={styles.jobMeta}>{formatProgress(item)}</Text>
            <View style={styles.actions}>
              <Pressable style={styles.action} onPress={() => pause(item.id)}>
                <Text>Pause</Text>
              </Pressable>
              <Pressable style={styles.action} onPress={() => resume(item.id)}>
                <Text>Resume</Text>
              </Pressable>
              <Pressable style={styles.action} onPress={() => cancel(item.id)}>
                <Text>Cancel</Text>
              </Pressable>
            </View>
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
  },
  title: {
    fontSize: 22,
    fontWeight: "600",
    marginBottom: 12,
  },
  error: {
    color: "#b00020",
    marginBottom: 8,
  },
  startButton: {
    backgroundColor: "#1b4dff",
    padding: 12,
    borderRadius: 8,
    alignItems: "center",
    marginBottom: 12,
  },
  startButtonText: {
    color: "#ffffff",
    fontWeight: "600",
  },
  card: {
    padding: 12,
    borderRadius: 8,
    backgroundColor: "#f2f4f8",
    marginBottom: 12,
  },
  jobTitle: {
    fontSize: 16,
    fontWeight: "600",
  },
  jobMeta: {
    color: "#4b5563",
  },
  actions: {
    flexDirection: "row",
    gap: 8,
    marginTop: 8,
  },
  action: {
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 6,
    backgroundColor: "#e2e8f0",
  },
});
