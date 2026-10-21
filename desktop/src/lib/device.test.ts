import { describe, expect, it } from "vitest";
import { deviceDetails, deviceNickname, deviceTitle } from "./device";

describe("device naming", () => {
  // Found on a real phone: a Galaxy A25 5G whose user-set name is "Galaxy s23".
  const a25 = { model: "Galaxy A25 5G", modelCode: "SM-A256E", name: "Galaxy s23", manufacturer: "Samsung", platform: "android" as const, osVersion: "16" };

  it("titles a phone by its model and keeps the user name as a nickname", () => {
    expect(deviceTitle(a25)).toBe("Galaxy A25 5G");
    expect(deviceNickname(a25)).toBe("Galaxy s23");
    expect(deviceDetails(a25)).toBe("Samsung SM-A256E · Android 16");
  });

  it("hides a nickname that only repeats the model", () => {
    expect(deviceNickname({ model: "iPhone 16", name: "iphone  16" })).toBeNull();
    expect(deviceDetails({ manufacturer: "Apple", model: "iPhone 16", modelCode: null, platform: "ios", osVersion: "18.4" })).toBe("Apple · iOS 18.4");
  });

  it("falls back to the name when an old app version sends no model", () => {
    expect(deviceTitle({ model: " ", name: "Phone" })).toBe("Phone");
  });
});
