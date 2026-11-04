//go:build android

package owenbatch

import upstream "github.com/exclavenetwork/libexclavecore"

// configureNetwork uses upstream's physical-network resolver and protect dialer.
// the resolver must stay alive while the upstream process-wide dialer is in use.
func ConfigureNetwork(resolver upstream.LocalResolver, protectPath string) {
	bridge := new(upstream.V2RayInstance)
	bridge.WithLocalResolver(resolver)
	bridge.WithAlternativeSystemDialer(protectPath)
}
