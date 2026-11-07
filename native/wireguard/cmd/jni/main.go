//go:build android || jni

// SPDX-License-Identifier: GPL-3.0-only

// This package is built with -buildmode=c-shared. The C shim exposes the exact
// NativeTunnel JNI methods; ordinary `go test ./...` tests the core without a JDK.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"errors"
	"sync"

	wg "app.morphe/wireguard"
)

var current struct {
	sync.Mutex
	engine *wg.Engine
}

//export morphe_wg_start
func morphe_wg_start(config, username, password *C.char, port C.int) (result C.int) {
	result = -3
	defer func() {
		if recover() != nil {
			result = -3
		}
	}()
	current.Lock()
	defer current.Unlock()
	if current.engine != nil {
		return -2
	}
	engine, err := wg.Start(C.GoString(config), C.GoString(username), C.GoString(password), int(port))
	if err != nil {
		var configErr *wg.ConfigError
		if errors.As(err, &configErr) {
			return -1
		}
		return -3
	}
	current.engine = engine
	return C.int(engine.Port())
}

//export morphe_wg_stop
func morphe_wg_stop() {
	current.Lock()
	defer current.Unlock()
	if current.engine != nil {
		current.engine.Close()
		current.engine = nil
	}
}

//export morphe_wg_validate
func morphe_wg_validate(config *C.char) *C.char {
	if err := wg.Validate(C.GoString(config)); err != nil {
		// Validate only returns deliberately sanitized ConfigError messages.
		return C.CString(err.Error())
	}
	return nil
}

func main() {}
