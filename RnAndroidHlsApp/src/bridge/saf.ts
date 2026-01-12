import { NativeModules, Platform } from "react-native";

interface SafModule {
  pickDirectory(): Promise<string | null>;
}

const SafNative = NativeModules.SafModule as SafModule | undefined;

export async function pickDirectory(): Promise<string | null> {
  if (Platform.OS !== "android") {
    return null;
  }
  if (!SafNative) {
    throw new Error("SafModule is not available");
  }
  return SafNative.pickDirectory();
}
