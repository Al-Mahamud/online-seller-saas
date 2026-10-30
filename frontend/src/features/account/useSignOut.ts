import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { api, call } from "@/api/client";
import { signedOut } from "@/api/session";

export function useSignOut() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation({
    mutationFn: () => call(api.POST("/api/v1/auth/logout")),
    onSuccess: async () => {
      signedOut(queryClient);
      await navigate({ to: "/sign-in" });
    },
  });
}
