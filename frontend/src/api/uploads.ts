import { api, call } from "./client";

/** An image for a logo or a product, up to 8 MB; the answer's id is then attached to one of them. */
export function uploadImage(file: File) {
  const data = new FormData();
  data.append("file", file);
  // The generated type calls a file a string; the browser sends the form data as it is.
  return call(api.POST("/api/v1/uploads", { body: { file: "" }, bodySerializer: () => data }));
}

/** A sheet of products, .xlsx or .csv, up to 10 MB: the server reads it and guesses its columns. */
export function uploadImport(file: File) {
  const data = new FormData();
  data.append("file", file);
  return call(api.POST("/api/v1/imports", { body: { file: "" }, bodySerializer: () => data }));
}
