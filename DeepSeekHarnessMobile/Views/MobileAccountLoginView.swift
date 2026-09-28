import SwiftUI

struct MobileAccountLoginView: View {
    @EnvironmentObject private var hosts: MultiGatewayStore
    @Environment(\.dismiss) private var dismiss
    @State private var origin = ""
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    @State private var task: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("HTTPS 服务器地址", text: $origin)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("用户名", text: $username)
                        .textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("密码", text: $password).textContentType(.password)
                } footer: {
                    Text("登录现有 Harness 账号。登录后可在主机列表切换账号。")
                }
                .disabled(task != nil)
                if task != nil { ProgressView() }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("账号登录")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { task?.cancel(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("登录") {
                        error = nil
                        task = Task {
                            defer { task = nil }
                            do {
                                try await hosts.loginAccount(origin: origin, username: username, password: password)
                                password = ""
                                dismiss()
                            } catch is CancellationError {
                                // Dismissing the sheet cancels login without retaining the password.
                            } catch { self.error = error.localizedDescription }
                        }
                    }
                    .disabled(task != nil || origin.isEmpty || username.isEmpty || password.isEmpty)
                }
            }
        }
        .interactiveDismissDisabled(task != nil)
        .onDisappear { task?.cancel(); password = "" }
    }
}
