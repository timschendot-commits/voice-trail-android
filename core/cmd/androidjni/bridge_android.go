//go:build android && cgo

package main

/*
#include <stdlib.h>
*/
import "C"

import "voicetrail.local/core/engine"

var tasks = engine.New()

//export VTStart
func VTStart(kind *C.char) *C.char {
	id := tasks.Start(C.GoString(kind))
	if id == "" {
		return nil
	}
	return C.CString(id)
}

//export VTEvent
func VTEvent(id, kind *C.char) C.int {
	if tasks.Event(C.GoString(id), C.GoString(kind)) {
		return 1
	}
	return 0
}

//export VTDispatch
func VTDispatch(id, tool *C.char) C.int {
	return C.int(tasks.Dispatch(C.GoString(id), C.GoString(tool)))
}

//export VTClose
func VTClose(id *C.char) {
	tasks.Close(C.GoString(id))
}

func main() {}
