// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "util/thrift_client.h"

#include <sys/poll.h>
#include <sys/types.h>

#include <ostream>
#include <string>

#include "gutil/strings/substitute.h"
#include "util/monotime.h"
#include "util/time.h"

namespace starrocks {

Status ThriftClientImpl::open() {
    try {
        if (!_transport->isOpen()) {
            _transport->open();
        }
    } catch (const apache::thrift::transport::TTransportException& e) {
        try {
            _transport->close();
        } catch (const apache::thrift::transport::TTransportException& e) {
            VLOG(2) << "Error closing socket to: " << ipaddress() << ":" << port() << ", ignoring (" << e.what() << ")";
        }
        // In certain cases in which the remote host is overloaded, this failure can
        // happen quite frequently. Let's print this error message without the stack
        // trace as there aren't many callers of this function.
        const std::string& err_msg =
                strings::Substitute("Couldn't open transport for $0:$1 ($2)", ipaddress(), port(), e.what());
        VLOG(2) << err_msg;
        return Status::ThriftRpcError(err_msg);
    }
    return Status::OK();
}

Status ThriftClientImpl::open_with_retry(int num_tries, int wait_ms) {
    DCHECK_GE(wait_ms, 0);
    Status status;
    int try_count = 0L;

    while (num_tries <= 0 || try_count < num_tries) {
        ++try_count;
        status = open();

        if (status.ok()) {
            return status;
        }

        LOG(INFO) << "Unable to connect to " << _ipaddress << ":" << _port;

        if (num_tries < 0) {
            LOG(INFO) << "(Attempt " << try_count << ", will retry indefinitely)";
        } else {
            LOG(INFO) << "(Attempt " << try_count << " of " << num_tries << ")";
        }

        SleepFor(MonoDelta::FromMilliseconds(wait_ms));
    }

    return status;
}

void ThriftClientImpl::close() {
    try {
        if (_transport != nullptr && _transport->isOpen()) _transport->close();
    } catch (const apache::thrift::transport::TTransportException& e) {
        LOG(INFO) << "Error closing connection to: " << ipaddress() << ":" << port() << ", ignoring (" << e.what()
                  << ")";
        // Forcibly close the socket (since the transport may have failed to get that far
        // during close())
        try {
            if (_socket != nullptr) _socket->close();
        } catch (const apache::thrift::transport::TTransportException& e) {
            LOG(INFO) << "Error closing socket to: " << ipaddress() << ":" << port() << ", ignoring (" << e.what()
                      << ")";
        }
    }
}

void ThriftClientImpl::update_active_time() {
    _last_active_time = MonotonicMillis();
}

bool ThriftClientImpl::is_active() {
    if (MonotonicMillis() - _last_active_time > config::thrift_rpc_connection_max_valid_time_ms) {
        return false;
    }
    // The server side does not actively send requests to the client.
    // If the POLLIN event is triggered, then the server side is actively disconnecting.
    pollfd fds[1];
    fds[0].fd = _socket->getSocketFD();
    fds[0].events = POLLIN;
    int ret = poll(fds, 1, 0);
    return ret == 0;
}

} // namespace starrocks
