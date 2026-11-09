import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { api, call } from "@/api/client";
import { meQuery, signedOut } from "@/api/session";

export function useSignOut() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () => call(api.POST("/api/v1/auth/logout")),
    onSuccess: async () => {
      // Signed out for the sign-in page's check, but the rest emptied only once the app's pages are
      // gone: emptied under them, they would ask again and be refused.
      await queryClient.cancelQueries();
      queryClient.setQueryData(meQuery.queryKey, null);
      await navigate({ to: "/sign-in" });
      signedOut(queryClient);
    },
  });
}
