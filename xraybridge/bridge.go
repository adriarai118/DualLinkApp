// Package xraybridge embeds Xray-core for DualLink.
//
// The key piece is the dialer controller: Xray calls it for every outgoing
// socket (TCP and UDP) right before connecting. We hand the raw fd to the
// Android side, which binds it to Wi-Fi or mobile data. That spreads the
// proxy's own connections across both links, so traffic through a VPN config
// is aggregated too.
package xraybridge

import (
	"errors"
	"strings"
	"sync"
	"syscall"

	core "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
	_ "golang.org/x/mobile/bind"
)

// Protector is implemented on the Android side.
// Protect must bind fd to a network and return true, or return false to fail the dial.
type Protector interface {
	Protect(fd int, network string, address string) bool
}

var (
	mu         sync.Mutex // guards instance
	instance   *core.Instance
	pmu        sync.Mutex // guards protector (separate so dials never wait on Start/Stop)
	protector  Protector
	registered bool
)

// SetProtector installs the per-socket callback. Safe to call more than once.
func SetProtector(p Protector) error {
	pmu.Lock()
	defer pmu.Unlock()
	protector = p
	if registered {
		return nil
	}
	registered = true
	return internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error {
		pmu.Lock()
		p := protector
		pmu.Unlock()
		if p == nil {
			return nil
		}
		ok := false
		if err := c.Control(func(fd uintptr) {
			ok = p.Protect(int(fd), network, address)
		}); err != nil {
			return err
		}
		if !ok {
			return errors.New("duallink: no link available for " + address)
		}
		return nil
	})
}

// Start runs Xray with a full JSON config.
func Start(configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		_ = instance.Close()
		instance = nil
	}
	cfg, err := serial.LoadJSONConfig(strings.NewReader(configJSON))
	if err != nil {
		return err
	}
	inst, err := core.New(cfg)
	if err != nil {
		return err
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return err
	}
	instance = inst
	return nil
}

// Stop shuts Xray down.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		_ = instance.Close()
		instance = nil
	}
}

// Version returns the Xray-core version string.
func Version() string {
	return core.Version()
}
