$(function(){
    var baseURL = "";

    $("#getAllBtn").click(getAllCircuits);
    $("#closeAllBtn").click(closeAllCircuits);

    getAllCircuits();

    function showError(message){
        $("#circuitsErrorLabel").show();
        $("#circuitsErrorLabel").html(message);
    }

    function hideError(){
        $("#circuitsErrorLabel").hide();
    }

    function getAllCircuits(){
        hideError();
        $("#circuitsTable > tbody").html("");

        $.ajax({url: baseURL+"/playground/server/queuecircuitbreaker/circuit/", dataType:"json"}).done(function(data) {
            if (data) {
                $.each(data, function(circuitHash, circuit) {
                    var $tableRow = $('<tr>')
                        .data("circuitHash", circuitHash)
                        .appendTo($('table#circuitsTable #circuitsTableBody'))
                        .append($('<td>').text(circuit.infos.circuit))
                        .append($('<td>').html(buildStatusLabel(circuit.status)))
                        .append($('<td>').text(circuit.infos.failRatio));

                    if(circuit.status != 'closed'){
                        $tableRow.append($('<td>')
                            .append($('<button>')
                                .text('close')
                                .addClass('circuitCloseButton btn btn-danger')
                                .on('click', closeCircuit)
                            ));
                    } else {
                        $tableRow.append($('<td>'));
                    }
                });
            }
        }).fail(function(xhr){
            showError("Failed to get all circuits: " + jqXHR.status + " " + jqXHR.responseText);
        });
    }

    function buildStatusLabel(status){
        var upperStatus = status.toUpperCase()
        var name;
        switch (upperStatus){
            case "OPEN":
                name = "danger";
                break;
            case "HALF_OPEN":
                name = "warning";
                break;
            case "CLOSED":
                name = "success";
                break;
            default:
                name = "default"
        }
        return "<span class='label label-"+name+" circuitStatusLabel'>"+upperStatus+"</span>"
    }

    function closeCircuit(e){
        var row = e.target.closest('tr');
        var circuitHash = $(row).data().circuitHash;
        $.ajax({
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify({ "status": "closed" }),
            url: baseURL+"/playground/server/queuecircuitbreaker/circuit/" + circuitHash + "/status",
            success: function(data){
                getAllCircuits();
            },
            error: function(jqXHR){
                showError("Failed to close circuit: " + jqXHR.status + " " + jqXHR.responseText);
            }
        });
    }

    function closeAllCircuits(){
        $.ajax({
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify({ "status": "closed" }),
            url: baseURL+"/playground/server/queuecircuitbreaker/circuit/_all/status",
            success: function(){
                getAllCircuits();
            },
            error: function(){
                showError("Failed to close all circuits");
            }
        });
    }
});